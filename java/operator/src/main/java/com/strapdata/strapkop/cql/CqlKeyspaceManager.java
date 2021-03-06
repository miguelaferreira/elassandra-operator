/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.k8s.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.datacenter.CqlStatus;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterPhase;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterStatus;
import com.strapdata.strapkop.plugins.Plugin;
import com.strapdata.strapkop.plugins.PluginRegistry;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateAction;
import com.strapdata.strapkop.sidecar.JmxmpElassandraProxy;
import io.kubernetes.client.openapi.models.V1Pod;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Completable;
import io.reactivex.CompletableSource;
import io.reactivex.Flowable;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Manage keyspace creation, adjust the replication factor for some <b>existing</b> keyspaces, and trigger repairs/cleanups accordingly.
 * Keyspace reconciliation must be made before role reconciliation.
 */
@Singleton
@Infrastructure
public class CqlKeyspaceManager extends AbstractManager<CqlKeyspace> {

    private static final Logger logger = LoggerFactory.getLogger(CqlKeyspaceManager.class);

    public static final Set<CqlKeyspace> SYSTEM_KEYSPACES = ImmutableSet.of(
            new CqlKeyspace().withName("system_auth").withRf(3).withRepair(true).setCreateIfNotExists(false),
            new CqlKeyspace().withName("system_distributed").withRf(3).withRepair(false).setCreateIfNotExists(false),
            new CqlKeyspace().withName("system_traces").withRf(3).withRepair(false).setCreateIfNotExists(false));

    final K8sResourceUtils k8sResourceUtils;
    final JmxmpElassandraProxy jmxmpElassandraProxy;

    public CqlKeyspaceManager(final K8sResourceUtils k8sResourceUtils,
                              final JmxmpElassandraProxy jmxmpElassandraProxy,
                              final MeterRegistry meterRegistry) {
        super(meterRegistry);
        this.k8sResourceUtils = k8sResourceUtils;
        this.jmxmpElassandraProxy = jmxmpElassandraProxy;
    }

    private String elasticAdminKeyspaceName(DataCenter dataCenter) {
        return (dataCenter.getSpec().getElasticsearch().getDatacenterGroup() != null)
                ? "elastic_admin_" + dataCenter.getSpec().getElasticsearch().getDatacenterGroup()
                : "elastic_admin";
    }

    /**
     * Create and adjust keyspace RF
     *
     * @param dataCenterUpdateAction
     * @return
     * @throws StrapkopException
     */
    public Single<Boolean> reconcileKeyspaces(final DataCenterUpdateAction dataCenterUpdateAction, Boolean updateStatus, final CqlSessionSupplier sessionSupplier, PluginRegistry pluginRegistry) {
        DataCenter dataCenter = dataCenterUpdateAction.dataCenter;
        return Single.just(updateStatus)
                .map(needDcStatusUpdate -> {
                    // init keyspaces for the datacenters
                    for (CqlKeyspace keyspace : SYSTEM_KEYSPACES) {
                        addIfAbsent(dataCenter, keyspace.name, () -> keyspace);
                    }
                    String elasticAdminKeyspaceName = elasticAdminKeyspaceName(dataCenter);
                    if (dataCenter.getSpec().getElasticsearch().getEnabled()) {
                        addIfAbsent(dataCenter, elasticAdminKeyspaceName, () -> new CqlKeyspace()
                                .withName(elasticAdminKeyspaceName).withRf(3).withRepair(true).withCreateIfNotExists(false));
                    } else {
                        remove(dataCenter, elasticAdminKeyspaceName);
                    }
                    logger.trace("manager={}", get(dataCenter));
                    return needDcStatusUpdate;
                })
                .flatMap(needDcStatusUpdate -> {
                    // import managed keyspace from plugins
                    List<CompletableSource> todoList = new ArrayList<>();
                    for (Plugin plugin : pluginRegistry.plugins()) {
                        if (plugin.isActive(dataCenter))
                            todoList.add(plugin.syncKeyspaces(CqlKeyspaceManager.this, dataCenter));
                    }
                    return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()])).toSingleDefault(needDcStatusUpdate);
                })
                .flatMap(needDcStatusUpdate -> {
                    // create keyspace if needed
                    List<CompletableSource> todoList = new ArrayList<>();
                    logger.trace("manager={}", get(dataCenter));
                    if (get(dataCenter) != null) {
                        for (CqlKeyspace ks : get(dataCenter).values()) {
                            if (!dataCenterUpdateAction.dataCenterStatus.getKeyspaceManagerStatus().getKeyspaces().contains(ks.name)) {
                                dataCenterUpdateAction.dataCenterStatus.getKeyspaceManagerStatus().getKeyspaces().add(ks.name);
                                needDcStatusUpdate = true;
                                if (ks.createIfNotExists) {
                                    try {
                                        todoList.add(ks.createIfNotExistsKeyspace(dataCenter, dataCenterUpdateAction.dataCenterStatus, sessionSupplier).ignoreElement());
                                    } catch (Exception e) {
                                        logger.warn("datacenter=" + dataCenter.id() + " Failed to create keyspace=" + ks.name, e);
                                    }
                                }
                            }
                        }
                    }
                    return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()])).toSingleDefault(needDcStatusUpdate);
                })
                .flatMap(needDcStatusUpdate -> {
                    // reconcile keyspace according to the current DC size
                    // if the last observed replicas and current replicas differ, update keyspaces
                    if (!Optional.ofNullable(dataCenterUpdateAction.dataCenterStatus.getKeyspaceManagerStatus().getReplicas()).orElse(0).equals(dataCenter.getSpec().getReplicas())) {
                        List<CompletableSource> todoList = new ArrayList<>();
                        logger.debug("manager={}", get(dataCenter));
                        for (CqlKeyspace keyspace : get(dataCenter).values()) {
                            try {
                                if (!keyspace.reconcilied() || keyspace.reconcileWithDcSize < keyspace.rf || dataCenter.getSpec().getReplicas() < keyspace.rf)
                                    todoList.add(updateKeyspaceReplicationMap(dataCenter, dataCenterUpdateAction.dataCenterStatus, keyspace.name, effectiveRF(dataCenter, keyspace.rf), sessionSupplier)
                                            .andThen(Completable.fromAction(() -> dataCenterUpdateAction.operation.getActions().add("Update keyspace RF for ["+keyspace.getName()+"]")))
                                    );
                            } catch (Exception e) {
                                logger.warn("datacenter=" + dataCenter.id() + " Failed to adjust RF for keyspace=" + keyspace, e);
                            }
                        }
                        // we set the current replicas in observed replicas to know if we need to update rf map
                        return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()]))
                                .andThen(Completable.fromAction(() -> {
                                    dataCenterUpdateAction.dataCenterStatus.getKeyspaceManagerStatus().setReplicas(dataCenter.getSpec().getReplicas());
                                }))
                                .toSingleDefault(true);
                    } else {
                        return Single.just(needDcStatusUpdate);
                    }
                });
    }

    /**
     * Compute the effective target RF.
     * If DC is scaling up, increase the RF by 1 to automatically stream data to the new node.
     *
     * @param dataCenter
     * @param targetRf
     * @return
     */
    int effectiveRF(DataCenter dataCenter, int targetRf) {
        return Math.max(1, Math.min(targetRf, Math.min(dataCenter.getStatus().getReadyReplicas(), dataCenter.getSpec().getReplicas())));
    }

    public void removeDatacenter(final DataCenter dataCenter, DataCenterStatus dataCenterStatus, CqlSessionSupplier sessionSupplier) throws Exception {
        // abort if dc is not running normally or not connected
        if (dataCenter.getStatus().getPhase().equals(DataCenterPhase.RUNNING) &&
                dataCenter.getStatus().getCqlStatus().equals(CqlStatus.ESTABLISHED)) {
            try {
                // adjust RF for system keyspaces
                for (CqlKeyspace keyspace : SYSTEM_KEYSPACES) {
                    updateKeyspaceReplicationMap(dataCenter, dataCenterStatus, keyspace.name, 0, sessionSupplier).blockingGet();
                }

                // monitor elastic_admin keyspace to reduce RF when scaling down the DC.
                updateKeyspaceReplicationMap(dataCenter, dataCenterStatus, elasticAdminKeyspaceName(dataCenter), 0, sessionSupplier).blockingGet();

                // adjust user keyspace RF
                if (get(dataCenter) != null) {
                    for (CqlKeyspace keyspace : get(dataCenter).values()) {
                        updateKeyspaceReplicationMap(dataCenter, dataCenterStatus, keyspace.name, 0, sessionSupplier).blockingGet();
                    }
                }
            } catch (Exception e) {
                logger.warn("datacenter=" + dataCenter.id() + " Unable to update Keyspace Replication Map due to '{}'", e.getMessage(), e);
            }
        }
        remove(dataCenter);
    }


    public Completable decreaseRfBeforeScalingDownDc(final DataCenter dataCenter, final DataCenterStatus dataCenterStatus, int targetDcSize, final CqlSessionSupplier sessionSupplier) throws Exception {
        if (get(dataCenter) != null &&
                dataCenter.getStatus().getPhase().equals(DataCenterPhase.RUNNING) &&
                dataCenter.getStatus().getCqlStatus().equals(CqlStatus.ESTABLISHED)) {
            Collection<CqlKeyspace> keyspaces = get(dataCenter).values();
            List<Completable> completables = new ArrayList<>(keyspaces.size());
            for (CqlKeyspace keyspace : keyspaces) {
                completables.add(updateKeyspaceReplicationMap(dataCenter, dataCenterStatus, keyspace.name, Math.min(keyspace.rf, targetDcSize), sessionSupplier));
            }
            return Completable.mergeArray(completables.toArray(new Completable[completables.size()])).onErrorComplete();
        }
        return Completable.complete();
    }

    private Completable updateKeyspaceReplicationMap(final DataCenter dc, final DataCenterStatus dataCenterStatus, final String keyspace, int targetRf, final CqlSessionSupplier sessionSupplier) throws Exception {
        return updateKeyspaceReplicationMap(dc, dataCenterStatus, dc.getSpec().getDatacenterName(), keyspace, targetRf, sessionSupplier, true);
    }

    /**
     * Remove the DC from replication map of all keyspaces.
     *
     * @param dc
     * @param sessionSupplier
     * @return
     * @throws Exception
     */
    public Completable removeDcFromReplicationMap(final DataCenter dc, final DataCenterStatus dataCenterStatus,
                                                  final String dcName, final CqlSessionSupplier sessionSupplier) throws Exception {
        return sessionSupplier.getSession(dc, dataCenterStatus)
                .flatMap(session -> Single.fromFuture(session.executeAsync("SELECT keyspace_name, replication FROM system_schema.keyspaces")))
                .flatMapCompletable(rs -> {
                    final Map<String, Integer> currentRfMap = new HashMap<>();
                    List<Completable> todoList = new ArrayList<>();
                    for (Row row : rs) {
                        final Map<String, String> replication = row.getMap("replication", String.class, String.class);
                        final Map<String, Integer> keyspaceReplicationMap = replication.entrySet().stream()
                                .filter(e -> !e.getKey().equals("class") && !e.getKey().equals("replication_factor"))
                                .collect(Collectors.toMap(Map.Entry::getKey, e -> Integer.parseInt(e.getValue())));
                        if (keyspaceReplicationMap.containsKey(dcName)) {
                            keyspaceReplicationMap.remove(dcName);
                            todoList.add(alterKeyspace(dc, dataCenterStatus, sessionSupplier, row.getString("keyspace_name"), keyspaceReplicationMap).ignoreElement());
                        }
                    }
                    return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()]));
                })
                .onErrorComplete(t -> {
                    logger.error("datacenter=" + dc.id() + " remove dc=" + dcName + " error:", t);
                    return true;
                });
    }

    /**
     * Alter rf map but keep other dc replication factor
     *
     * @throws StrapkopException
     */
    public Completable updateKeyspaceReplicationMap(final DataCenter dc, DataCenterStatus dataCenterStatus, String dcName, final String keyspace, int targetRf, final CqlSessionSupplier sessionSupplier, boolean triggerRepairOrCleanup) throws Exception {
        return sessionSupplier.getSessionWithSchemaAgreed(dc, dataCenterStatus)
                .flatMap(session ->
                        Single.fromFuture(session.executeAsync("SELECT keyspace_name, replication FROM system_schema.keyspaces WHERE keyspace_name = ?", keyspace))
                )
                .flatMapCompletable(rs -> {
                    Row row = rs.one();
                    if (row == null) {
                        logger.warn("datacenter={} keyspace={} does not exist, ignoring.", dc.id(), keyspace, dc.getMetadata().getName());
                        return Completable.complete();
                    }
                    final Map<String, String> replication = row.getMap("replication", String.class, String.class);
                    final String strategy = replication.get("class");
                    Objects.requireNonNull(strategy, "replication strategy cannot be null");

                    final Map<String, Integer> currentRfMap = replication.entrySet().stream()
                            .filter(e -> !e.getKey().equals("class") && !e.getKey().equals("replication_factor"))
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> Integer.parseInt(e.getValue())
                            ));
                    final int currentRf = currentRfMap.getOrDefault(dcName, 0);
                    logger.debug("datacenter={} keyspace={} currentRf={} targetRf={}", dc.id(), keyspace, currentRf, targetRf);

                    // increase sequentially RF by one and repair to avoid quorum read issue (and elassandra_operator login issue)
                    Completable todo = Completable.complete();
                    for (int rf = currentRf; rf <= targetRf; rf++) {
                        currentRfMap.put(dcName, rf);
                        final int rf2 = rf;
                        if (currentRfMap.entrySet().stream().filter(e -> e.getValue() > 0).count() > 0) {
                            todo = todo.andThen(alterKeyspace(dc, dataCenterStatus, sessionSupplier, keyspace, currentRfMap)
                                    .map(s -> {
                                        logger.debug("datacenter={} ALTER executed keyspace={} replicationMap={}", dc.id(), keyspace, currentRfMap);
                                        return s;
                                    })
                                    // check schema agreement and wait beyond the max schema agreement wait timeout
                                    .flatMap(s -> {
                                        if (!s.getCluster().getMetadata().checkSchemaAgreement())
                                            throw new IllegalStateException("No schema agreement");
                                        return Single.just(s);
                                    })
                                    .retryWhen((Flowable<Throwable> f) -> f.take(20).delay(6, TimeUnit.SECONDS))
                                    .map(s -> {
                                        logger.debug("datacenter={} ALTER applied keyspace={} replicationMap={}", dc.id(), keyspace, currentRfMap);
                                        CqlKeyspace cqlKeyspace = get(dc, keyspace);
                                        if (cqlKeyspace == null) {
                                            cqlKeyspace = new CqlKeyspace().withName(keyspace);
                                        }
                                        cqlKeyspace.setReconcilied(true);
                                        cqlKeyspace.setReconcileWithDcSize(rf2);
                                        put(dc, cqlKeyspace.name, cqlKeyspace);
                                        return s;
                                    })
                                    .flatMapCompletable(s -> {
                                        if (!triggerRepairOrCleanup)
                                            return Completable.complete();

                                        if (targetRf > currentRf) {
                                            // RF increased
                                            if (targetRf > 1) {
                                                logger.info("datacenter={} Need a repair for keyspace={}", dc.id(), keyspace);
                                                final String labelSelector = OperatorLabels.toSelector(ImmutableMap.of(
                                                        OperatorLabels.PARENT, dc.getMetadata().getName(),
                                                        OperatorLabels.APP, OperatorLabels.ELASSANDRA_APP));
                                                return Single.fromCallable(new Callable<Iterable<V1Pod>>() {
                                                    @Override
                                                    public Iterable<V1Pod> call() throws Exception {
                                                        return k8sResourceUtils.listNamespacedPods(dc.getMetadata().getNamespace(), null, labelSelector);
                                                    }
                                                }).flatMapCompletable(podList -> {
                                                    Completable todo2 = Completable.complete();
                                                    for (V1Pod pod : podList) {
                                                        logger.debug("Launch repair pod={} keyspace={}", pod.getMetadata().getName(), keyspace);
                                                        todo2 = todo2.andThen(jmxmpElassandraProxy.repair(ElassandraPod.fromName(dc, pod.getMetadata().getName()), keyspace));
                                                    }
                                                    return todo2;
                                                });
                                            }
                                        } else {
                                            // RF decreased
                                            if (targetRf < dc.getSpec().getReplicas()) {
                                                dc.getStatus().getNeedCleanupKeyspaces().add(keyspace);
                                                logger.info("datacenter={} cleanup required for keyspace={}", dc.id(), keyspace);
                                            }
                                        }
                                        return Completable.complete();
                                    }));
                        }
                    }
                    return todo;
                })
                .onErrorComplete(t -> {
                    if (!(t instanceof java.net.UnknownHostException))
                        logger.error("datacenter=" + dc.id() + " update RF keyspace=" + keyspace + " error:", t);
                    return true;
                });
    }

    private Single<Session> alterKeyspace(final DataCenter dc, DataCenterStatus dataCenterStatus, final CqlSessionSupplier sessionSupplier, final String name, Map<String, Integer> rfMap) throws Exception {
        return sessionSupplier.getSessionWithSchemaAgreed(dc, dataCenterStatus)
                .flatMap(session -> {
                    final String query = String.format(Locale.ROOT,
                            "ALTER KEYSPACE %s WITH replication = {'class': 'NetworkTopologyStrategy', %s};",
                            quote(name), stringifyRfMap(rfMap));
                    logger.debug("dc={} query={}", dc.id(), query);
                    return Single.fromFuture(session.executeAsync(query)).map(x -> session);
                });
    }

    private String stringifyRfMap(final Map<String, Integer> rfMap) {
        return rfMap.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> String.format("'%s': %d", e.getKey(), e.getValue()))
                .collect(Collectors.joining(", "));
    }

    private String quote(final String keyspace) {
        return "\"" + keyspace + "\"";
    }
}
