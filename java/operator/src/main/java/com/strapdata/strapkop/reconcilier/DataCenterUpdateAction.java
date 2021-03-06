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

package com.strapdata.strapkop.reconcilier;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.InetAddresses;
import com.strapdata.cassandra.k8s.ElassandraOperatorSeedProvider;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.DataCenterStatusCache;
import com.strapdata.strapkop.cache.StatefulsetCache;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRole;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.cql.CqlSessionHandler;
import com.strapdata.strapkop.k8s.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import com.strapdata.strapkop.model.k8s.datacenter.*;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.plugins.PluginRegistry;
import com.strapdata.strapkop.sidecar.JmxmpElassandraProxy;
import com.strapdata.strapkop.ssl.AuthorityManager;
import com.strapdata.strapkop.ssl.utils.X509CertificateAndPrivateKey;
import com.strapdata.strapkop.utils.BackupScheduler;
import com.strapdata.strapkop.utils.QuantityConverter;
import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.*;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Prototype;
import io.reactivex.Completable;
import io.reactivex.CompletableSource;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.vavr.Tuple4;
import lombok.Data;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.bouncycastle.operator.OperatorCreationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import javax.inject.Named;
import java.io.IOException;
import java.io.StringWriter;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.strapdata.strapkop.k8s.OperatorNames.ELASTICSEARCH_PORT_NAME;
import static com.strapdata.strapkop.k8s.OperatorNames.PROMETHEUS_PORT_NAME;

/**
 * The NodePort service has a DNS name and redirect to STS seed pods
 * see https://stackoverflow.com/questions/46456239/how-to-expose-a-headless-service-for-a-statefulset-externally-in-kubernetes
 */
@Prototype
public class DataCenterUpdateAction {
    private static final Logger logger = LoggerFactory.getLogger(DataCenterUpdateAction.class);

    private static final long MB = 1024 * 1024;
    private static final long GB = MB * 1024;

    public static final String OPERATOR_KEYSTORE_MOUNT_PATH = "/tmp/datacenter-keystore";
    public static final String OPERATOR_KEYSTORE = "keystore.p12";
    public static final String OPERATOR_KEYPASS = "changeit";


    public static final String KEY_JMX_PASSWORD = "cassandra.jmx_password";
    public static final String KEY_SHARED_SECRET = "shared-secret.yaml";
    public static final String KEY_REAPER_PASSWORD = "cassandra.reaper_password";

    public static final long CASSANDRA_USER_ID = 999L;
    public static final long CASSANDRA_GROUP_ID = 999L;

    private final ApplicationContext context;
    private final CoreV1Api coreApi;
    private final AppsV1Api appsApi;
    private final CustomObjectsApi customObjectsApi;
    private final K8sResourceUtils k8sResourceUtils;
    private final AuthorityManager authorityManager;

    private final long startTime;

    @Named("operation")
    public final Operation operation;

    @Named("dataCenter")
    public final DataCenter dataCenter;

    private final Key key;
    private Zones zones;
    private List<String> sibilingDcs;

    public final V1ObjectMeta dataCenterMetadata;
    public final DataCenterSpec dataCenterSpec;
    public final DataCenterStatus dataCenterStatus;

    private final CqlRoleManager cqlRoleManager;
    private final CqlKeyspaceManager cqlKeyspaceManager;
    private final PluginRegistry pluginRegistry;
    private final JmxmpElassandraProxy jmxmpElassandraProxy;

    private final OperatorConfig operatorConfig;

    private final StatefulsetCache statefulsetCache;
    private final DataCenterStatusCache dataCenterStatusCache;
    private final  SharedIndexInformer<V1ServiceAccount> saSharedIndexInformer;

    final SharedInformerFactory sharedInformerFactory;

    private final BackupScheduler backupScheduler;

    public final Builder builder = new Builder();

    public DataCenterUpdateAction(final ApplicationContext context,
                                  final SharedInformerFactory sharedInformerFactory,
                                  final CoreV1Api coreApi,
                                  final AppsV1Api appsApi,
                                  final CustomObjectsApi customObjectsApi,
                                  final K8sResourceUtils k8sResourceUtils,
                                  final AuthorityManager authorityManager,
                                  final CqlRoleManager cqlRoleManager,
                                  final CqlKeyspaceManager cqlKeyspaceManager,
                                  final StatefulsetCache statefulsetCache,
                                  final DataCenterStatusCache dataCenterStatusCache,
                                  final JmxmpElassandraProxy jmxmpElassandraProxy,
                                  @Parameter("dataCenter") DataCenter dataCenter,
                                  @Parameter("operation") Operation operation,
                                  final OperatorConfig operatorConfig,
                                  final BackupScheduler backupScheduler,
                                  final PluginRegistry pluginRegistry) {
        this.context = context;
        this.sharedInformerFactory = sharedInformerFactory;
        this.coreApi = coreApi;
        this.appsApi = appsApi;
        this.customObjectsApi = customObjectsApi;
        this.k8sResourceUtils = k8sResourceUtils;
        this.authorityManager = authorityManager;
        this.operatorConfig = operatorConfig;

        this.operation = operation;
        this.startTime = System.currentTimeMillis();
        this.operation.setPendingInMs(startTime - operation.getLastTransitionTime().getTime());

        this.dataCenter = dataCenter;
        this.dataCenterMetadata = dataCenter.getMetadata();
        this.dataCenterSpec = dataCenter.getSpec();

        this.dataCenterStatusCache = dataCenterStatusCache;
        this.statefulsetCache = statefulsetCache;
        this.saSharedIndexInformer = sharedInformerFactory.getExistingSharedIndexInformer(V1ServiceAccount.class);

        this.cqlRoleManager = cqlRoleManager;
        this.cqlKeyspaceManager = cqlKeyspaceManager;

        this.jmxmpElassandraProxy = jmxmpElassandraProxy;

        this.backupScheduler = backupScheduler;
        this.pluginRegistry = pluginRegistry;

        this.key = new Key(dataCenterMetadata);
        this.dataCenterStatus = dataCenterStatusCache.getOrDefault(key, dataCenter.getStatus());

        SharedIndexInformer<V1Node> nodeSharedIndexInformer = sharedInformerFactory.getExistingSharedIndexInformer(V1Node.class);
        this.zones = new Zones(dataCenterStatus, nodeSharedIndexInformer.getIndexer().list(), this.statefulsetCache.getOrDefault(key, new TreeMap<>()));
    }

    // sibiling DC are DC in the same cluster, same namespace
    public DataCenterUpdateAction setSibilingDc(List<String> sibilingDcs) {
        this.sibilingDcs = sibilingDcs;
        return this;
    }

    public Single<Optional<V1ConfigMap>> readUserConfigMap() {
        return dataCenterSpec.getUserConfigMapVolumeSource() == null ?
                Single.just(Optional.empty()) :
                k8sResourceUtils.readNamespacedConfigMap(dataCenterMetadata.getNamespace(), dataCenterSpec.getUserConfigMapVolumeSource().getName())
                        .map(cfg -> Optional.of(cfg))
                .onErrorResumeNext(t -> {
                    // configmap may be not found
                    logger.debug("ConfigMap={}/{} not found", dataCenterSpec.getUserConfigMapVolumeSource().getName(), dataCenterMetadata.getNamespace());
                    return Single.just(Optional.empty());
                });
    }

    public void endOperation(String action) {
        operation.getActions().add(action);
        endOperation();
    }

    public void endOperation() {
        long endTime = System.currentTimeMillis();
        operation.setDurationInMs(endTime - startTime);
        List<Operation> history = dataCenterStatus.getOperationHistory();
        history.add(0, this.operation);
        if (history.size() > operatorConfig.getOperationHistoryDepth())
            history.remove(operatorConfig.getOperationHistoryDepth());
        dataCenterStatus.setOperationHistory(history);
        logger.trace("update status datacenterStatus={}", dataCenterStatus);
    }

    /**
     * Init a new datacenter with its first node
     */
    public Completable initDatacenter() throws IOException, ApiException {
        logger.debug("########## datacenter={} new datacenter", dataCenter.id());
        return Single.zip(
                k8sResourceUtils.createOrReplaceNamespacedService(builder.buildServiceNodes()),
                k8sResourceUtils.createOrReplaceNamespacedService(builder.buildServiceElasticsearch()),
                k8sResourceUtils.createOrReplaceNamespacedService(builder.buildServiceExternal()),
                k8sResourceUtils.createOrReplaceNamespacedService(builder.buildServiceAdmin()),
                Tuple4::new
        )
                .flatMap(tuple -> {
                    // create cluster secret if not exists
                    V1ObjectMeta clusterSecretMeta = builder.clusterObjectMeta(OperatorNames.clusterSecret(dataCenter),
                            ImmutableMap.of(OperatorLabels.CREDENTIAL, Boolean.TRUE.toString()));
                    return k8sResourceUtils.readOrCreateNamespacedSecret(clusterSecretMeta, () -> {
                        V1Secret secret = new V1Secret().metadata(clusterSecretMeta).type("Opaque");
                        secret.putStringDataItem(CqlRole.KEY_CASSANDRA_PASSWORD, UUID.randomUUID().toString());
                        secret.putStringDataItem(CqlRole.KEY_ELASSANDRA_OPERATOR_PASSWORD, UUID.randomUUID().toString());
                        secret.putStringDataItem(CqlRole.KEY_ADMIN_PASSWORD, UUID.randomUUID().toString());
                        secret.putStringDataItem(KEY_JMX_PASSWORD, UUID.randomUUID().toString());
                        secret.putStringDataItem(KEY_SHARED_SECRET, "aaa.shared_secret: " + UUID.randomUUID().toString());
                        secret.putStringDataItem(KEY_REAPER_PASSWORD, UUID.randomUUID().toString());
                        logger.debug("Created new cluster secret={}/{}", clusterSecretMeta.getName(), clusterSecretMeta.getNamespace());
                        return secret;
                    });
                })
                .map(s -> {
                    Map<String, String> passwords = s.getData().entrySet().stream().collect(Collectors.toMap(e -> e.getKey(), e -> new String(e.getValue())));
                    if (context.getEnvironment().getActiveNames().contains("test")) {
                        logger.warn("datacenter={} secret passwords={}", dataCenter.id(), passwords);
                    }
                    return passwords;
                })
                .flatMap(passwords -> {
                    // create rc file
                    return k8sResourceUtils.readOrCreateNamespacedSecret(builder.clusterObjectMeta(OperatorNames.clusterRcFilesSecret(dataCenter)),
                            () -> builder.buildSecretRcFile("admin", passwords.get(CqlRole.KEY_ADMIN_PASSWORD)))
                            .map(s -> {
                                passwords.clear();
                                return passwords;
                            });
                })
                .flatMap(passwords -> {
                    // create SSL keystore if not exists
                    return !dataCenterSpec.getCassandra().getSsl() ?
                            Single.just(passwords) :
                            authorityManager.getSingle(dataCenterMetadata.getNamespace(), dataCenterSpec.getClusterName())
                                    .flatMap(x509CertificateAndPrivateKey -> {
                                        V1ObjectMeta keystoreMeta = builder.dataCenterObjectMeta(OperatorNames.keystoreSecret(dataCenter));
                                        return k8sResourceUtils.readOrCreateNamespacedSecret(keystoreMeta,
                                                () -> {
                                                    try {
                                                        return builder.buildSecretKeystore(x509CertificateAndPrivateKey);
                                                    } catch (GeneralSecurityException | IOException | OperatorCreationException e) {
                                                        throw new RuntimeException(e);
                                                    }
                                                });
                                    });
                })
                .flatMap(password -> builder.buildPodDisruptionBudget())    // build one PDB per dc
                .flatMap(pdb -> readUserConfigMap())
                .flatMapCompletable(optionalUserConfig -> {
                    ConfigMapVolumeMounts configMapVolumeMounts = new ConfigMapVolumeMounts(optionalUserConfig);

                    if (dataCenterStatus.getRackStatuses().isEmpty() && !zones.zoneMap.isEmpty()) {
                        // choose the first k8s node, and build config and sts
                        RackStatus rackStatus = new RackStatus()
                                .withDesiredReplicas(1)
                                .withHealth(Health.RED)
                                .withIndex(0)
                                .withName(zones.zoneMap.navigableKeySet().first())
                                .withFingerprint(configMapVolumeMounts.fingerPrint());

                        dataCenterStatus.getRackStatuses().put(0, rackStatus);
                        dataCenterStatus.setHealth(Health.RED);
                        dataCenterStatus.setPhase(DataCenterPhase.RUNNING);
                        dataCenterStatus.setObservedGeneration(dataCenterMetadata.getGeneration());
                        configMapVolumeMounts.setRack(rackStatus);
                        return configMapVolumeMounts.createOrReplaceNamespacedConfigMaps()
                                // updateRack also call prepareDataCenterSnapshot
                                .andThen(builder.buildStatefulSetRack(rackStatus, configMapVolumeMounts)
                                        .flatMap(s -> {
                                            endOperation("Datacenter resources deployed");
                                            return k8sResourceUtils.updateDataCenterStatus(dataCenter, dataCenterStatus);
                                        }))
                                .ignoreElement();
                    } else {
                        return updateStateThenNextAction();
                    }
                });
    }


    /**
     * Update DC spec, and trigger the next action to the desired state
     */
    public Completable updateDatacenterSpec() throws Exception {
        logger.debug("########## datacenter={} generation/observedGeneration={}/{} resourceVersion={} spec updated replicas={}/{}",
                dataCenter.id(),
                dataCenterMetadata.getGeneration(), dataCenterStatus.getObservedGeneration(),
                dataCenterMetadata.getResourceVersion(),
                dataCenterStatus.getReadyReplicas(), dataCenterSpec.getReplicas());
        dataCenterStatus.setObservedGeneration(dataCenterMetadata.getGeneration());
        return updateStateThenNextAction();
    }

    public boolean statefulSetIsReady(V1StatefulSet sts) {
        V1StatefulSetStatus stsStatus = sts.getStatus();
        int replicas = ObjectUtils.defaultIfNull(stsStatus.getReplicas(), 0);
        int readyReplicas = ObjectUtils.defaultIfNull(stsStatus.getReadyReplicas(), 0);
        if (readyReplicas >= replicas) {
            logger.trace("datacenter={} sts={} ready", dataCenter.id(), sts.getMetadata().getName());
            return true;
        }
        logger.trace("datacenter={} sts={} NOT ready", dataCenter.id(), sts.getMetadata().getName());
        return false;
    }

    public boolean statefulSetIsUpToDate(V1StatefulSet sts) {
        V1StatefulSetStatus stsStatus = sts.getStatus();
        int replicas = ObjectUtils.defaultIfNull(stsStatus.getReplicas(), 0);
        int updatedReplicas = ObjectUtils.defaultIfNull(stsStatus.getUpdatedReplicas(), replicas);
        if ((stsStatus.getUpdateRevision() == null || stsStatus.getCurrentRevision().equals(stsStatus.getUpdateRevision()))
                && updatedReplicas == replicas ) {
            logger.trace("datacenter={} sts={} up-to-date", dataCenter.id(), sts.getMetadata().getName());
            return true;
        }
        logger.trace("datacenter={} sts={} NOT up-to-date", dataCenter.id(), sts.getMetadata().getName());
        return false;
    }

    public Completable taskDone(Task task) {
        logger.debug("########## datacenter={} task={} done => reconcile", dataCenter.id(), task.id());
        return updateStateThenNextAction();
    }

    public Completable updateStateThenNextAction() {
        return Completable.fromAction(() -> {
            for(RackStatus rackStatus : dataCenterStatus.getRackStatuses().values()) {
                V1StatefulSet sts = sharedInformerFactory.getExistingSharedIndexInformer(V1StatefulSet.class).getIndexer()
                        .getByKey(dataCenterMetadata.getNamespace()+"/"+OperatorNames.stsName(dataCenter, rackStatus.getIndex()));
                if (sts != null) {
                    rackStatus.setReadyReplicas(ObjectUtils.defaultIfNull(sts.getStatus().getReadyReplicas(), 0));
                    rackStatus.setProgressState(statefulSetIsUpToDate(sts) ? ProgressState.RUNNING : ProgressState.UPDATING);
                    rackStatus.setHealth(rackStatus.health());
                }
            }

            // update the DC total ready repliacs
            int totalReadyReplicas = dataCenterStatus.getRackStatuses().values().stream()
                    .map(r -> ObjectUtils.defaultIfNull(r.getReadyReplicas(), 0))
                    .reduce(0, (a, b) -> a + b);
            dataCenterStatus.setReadyReplicas(totalReadyReplicas);
            dataCenterStatus.setHealth(dataCenterStatus.health());
        }).andThen(nextAction(true));
    }

    Completable nextAction(final boolean updateStatus) {
        if (dataCenterSpec.isParked() && !(DataCenterPhase.PARKED.equals(dataCenterStatus.getPhase())))
            return parkDatacenter();

        if (!dataCenterSpec.isParked() && (DataCenterPhase.PARKED.equals(dataCenterStatus.getPhase())))
            return unparkDatacenter();

        if (DataCenterPhase.PARKED.equals(dataCenterStatus.getPhase()) && dataCenterSpec.isParked() && dataCenterStatus.getReadyReplicas() == 0) {
            return updateStatus ?
                    k8sResourceUtils.updateDataCenterStatus(dataCenter, dataCenterStatus).flatMapCompletable(dc -> {
                        logger.debug("datacenter={} updating status={}", dataCenter.id(), dataCenterStatus);
                        return Completable.complete();
                    }) :
                    Completable.complete();
        }

        // read user config map to check fingerprint
        return readUserConfigMap()
                .flatMapCompletable(optionalUserConfig -> {
                    ConfigMapVolumeMounts configMapVolumeMounts = new ConfigMapVolumeMounts(optionalUserConfig);
                    String currentFingerprint = dataCenterSpec.elassandraFingerprint() + "-" + configMapVolumeMounts.fingerPrint();
                    TreeMap<String, V1StatefulSet> statefulSetTreeMap = this.statefulsetCache.getOrDefault(key, new TreeMap<>());
                    boolean allStsReady = true;
                    for(RackStatus rackStatus : dataCenterStatus.getRackStatuses().values()) {
                        V1StatefulSet v1StatefulSet = statefulSetTreeMap.get(rackStatus.getName());
                        if (ProgressState.UPDATING.equals(rackStatus.getProgressState()) || v1StatefulSet == null || !statefulSetIsReady(v1StatefulSet)) {
                            allStsReady = false;
                            break;
                        }
                    }
                    logger.debug("allStsReady={} currentFingerprint={} rackStatus={}",
                            allStsReady, currentFingerprint, dataCenterStatus.getRackStatuses().values());

                    // don't update sts if another one is currently doing a rolling update.
                    if (allStsReady) {
                        for(RackStatus rackStatus : dataCenterStatus.getRackStatuses().values()) {
                            V1StatefulSet v1StatefulSet = statefulSetTreeMap.get(rackStatus.getName());
                            String stsFingerprint = v1StatefulSet == null ? null : v1StatefulSet.getSpec().getTemplate().getMetadata().getAnnotations().get(OperatorLabels.DATACENTER_FINGERPRINT);
                            // Trigger an update if ConfigMap fingerprint or DC generation are different
                            if (!currentFingerprint.equals(stsFingerprint)) {
                                logger.debug("datacenter={} fingerprint={} sts={} fingerprint={} not match => rolling update sts",
                                        dataCenter.id(), currentFingerprint, v1StatefulSet == null ? null : v1StatefulSet.getMetadata().getName(), stsFingerprint);

                                rackStatus.setFingerprint(currentFingerprint);
                                rackStatus.setProgressState(ProgressState.UPDATING);

                                configMapVolumeMounts.setRack(rackStatus);
                                return configMapVolumeMounts.createOrReplaceNamespacedConfigMaps()
                                        // updateRack also call prepareDataCenterSnapshot
                                        .andThen(builder.buildStatefulSetRack(rackStatus, configMapVolumeMounts)
                                                .flatMapCompletable(sts -> {
                                                    endOperation("updating sts=" + v1StatefulSet.getMetadata().getName() +" fingerprint=" + currentFingerprint + " rack=" + rackStatus.getName());
                                                    return k8sResourceUtils.updateDataCenterStatus(dataCenter, dataCenterStatus).ignoreElement();
                                                }));
                            }
                        }
                    }

                    // check if all racks STS have reached the desired state, otherwise wait next reconciliation
                    for(RackStatus rackStatus : dataCenterStatus.getRackStatuses().values()) {
                        V1StatefulSet v1StatefulSet = statefulSetTreeMap.get(rackStatus.getName());
                        if (ProgressState.UPDATING.equals(rackStatus.getProgressState())) {
                            logger.debug("v1StatefulSet={}/{} not up-to-date, waiting", v1StatefulSet.getMetadata().getName(), v1StatefulSet.getMetadata().getNamespace());
                            endOperation("noop, wait for rolling update completion in rack index=" + rackStatus.getIndex() + " name=" + rackStatus.getName());
                            return k8sResourceUtils.updateDataCenterStatus(dataCenter, dataCenterStatus).ignoreElement();
                        }
                    }

                    // check if need to scale up or down
                    int totalReplicas = dataCenterStatus.getRackStatuses().values().stream()
                            .map(r -> r.getDesiredReplicas())
                            .reduce(0, (a, b) -> a + b);
                    if (totalReplicas < dataCenter.getSpec().getReplicas() && Health.GREEN.equals(dataCenterStatus.health()))
                        return scaleUpDatacenter(configMapVolumeMounts);

                    // check if need to scale down (require a CQL connection to reduce some RF)
                    final CqlSessionHandler cqlSessionHandler = context.createBean(CqlSessionHandler.class, this.cqlRoleManager);
                    if (totalReplicas > dataCenter.getSpec().getReplicas() && Health.GREEN.equals(dataCenterStatus.health()))
                        return scaleDownDatacenter(configMapVolumeMounts, cqlSessionHandler)
                                .doFinally(() -> cqlSessionHandler.close());

                    // check if all racks STS are ready, otherwise wait next reconciliation
                    for(RackStatus rackStatus : dataCenterStatus.getRackStatuses().values()) {
                        V1StatefulSet v1StatefulSet = statefulSetTreeMap.get(rackStatus.getName());
                        if (v1StatefulSet != null && !statefulSetIsReady(v1StatefulSet)) {
                            logger.debug("v1StatefulSet={}/{} not ready, waiting", v1StatefulSet.getMetadata().getName(), v1StatefulSet.getMetadata().getNamespace());
                            endOperation("noop, wait for pods ready in rack index=" + rackStatus.getIndex() + " name=" + rackStatus.getName());
                            return k8sResourceUtils.updateDataCenterStatus(dataCenter, dataCenterStatus).ignoreElement();
                        }
                    }

                    // tack if we need to update the datacenter update status
                    Single<Boolean> doUpdate = updateStatus
                        ? k8sResourceUtils.updateDataCenterStatus(dataCenter, dataCenterStatus).map(o -> false)
                        : Single.just(false);

                    // manage roles, keyspaces, plugins, and backups
                    if (dataCenterStatus.getReadyReplicas() > 0 && dataCenterStatus.getBootstrapped() == true) {
                        doUpdate = doUpdate
                                .flatMap(status -> this.cqlKeyspaceManager.reconcileKeyspaces(this, status, cqlSessionHandler, pluginRegistry))
                                .flatMap(status -> this.cqlRoleManager.reconcileRole(this, status, cqlSessionHandler, pluginRegistry))
                                // manage plugins
                                .flatMap(s -> pluginRegistry.reconcileAll(this).map(b -> b || s));
                                //.flatMap(s -> Completable.fromAction(() -> backupScheduler.scheduleBackups(dataCenter)).toSingleDefault(s));
                    }

                    // update datacenter status
                    return doUpdate.flatMapCompletable(doStatusUpdate -> {
                        if (doStatusUpdate) {
                            logger.trace("datacenter={} updating status={}", dataCenter.id(), dataCenterStatus);
                            endOperation();
                            return k8sResourceUtils.updateDataCenterStatus(dataCenter, dataCenterStatus).ignoreElement();
                        }
                        return Completable.complete();
                    }).doFinally(() -> cqlSessionHandler.close());
                });
    }

    public Completable parkDatacenter() {
        List<CompletableSource> todoList = new ArrayList<>();
        TreeMap<String, V1StatefulSet> statefulSetTreeMap = this.statefulsetCache.get(key);
        for (V1StatefulSet v1StatefulSet : statefulSetTreeMap.values()) {
            int rackIndex = Integer.parseInt(v1StatefulSet.getMetadata().getLabels().get(OperatorLabels.RACKINDEX));
            RackStatus rackStatus = dataCenterStatus.getRackStatuses().get(rackIndex);
            logger.debug("DataCenter={} PARKING rack={}", dataCenter.id(), rackStatus);
            rackStatus.setHealth(Health.RED);
            v1StatefulSet.getSpec().setReplicas(0);
            todoList.add(k8sResourceUtils.replaceNamespacedStatefulSet(v1StatefulSet).ignoreElement());
        }
        return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()]))
                .toSingleDefault(dataCenter)
                .flatMap(s -> pluginRegistry.reconcileAll(this))
                // remove scheduled backups ?
                //.flatMap(s -> Completable.fromAction(() -> backupScheduler.scheduleBackups(dataCenter)).toSingleDefault(dataCenter)) // start backup when plugin are reconcilied.
                .flatMapCompletable(dataCenter1 -> {
                    dataCenterStatus.setPhase(DataCenterPhase.PARKED);
                    dataCenterStatus.setHealth(Health.RED);
                    endOperation("parked");
                    return k8sResourceUtils.updateDataCenterStatus(dataCenter, dataCenterStatus).ignoreElement();
                });
    }

    public Completable unparkDatacenter() {
        List<CompletableSource> todoList = new ArrayList<>();
        TreeMap<String, V1StatefulSet> statefulSetTreeMap = this.statefulsetCache.get(key);
        for (V1StatefulSet v1StatefulSet : statefulSetTreeMap.values()) {
            int rackIndex = Integer.parseInt(v1StatefulSet.getMetadata().getLabels().get(OperatorLabels.RACKINDEX));
            RackStatus rackStatus = dataCenterStatus.getRackStatuses().get(rackIndex);
            logger.debug("DataCenter={} UNPARKING rack={}", dataCenter.id(), rackStatus);

            v1StatefulSet.getSpec().setReplicas(rackStatus.getDesiredReplicas());
            todoList.add(k8sResourceUtils.replaceNamespacedStatefulSet(v1StatefulSet).ignoreElement());
        }
        return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()]))
                .toSingleDefault(dataCenter)
                .flatMapCompletable(dataCenter1 -> {
                    dataCenterStatus.setPhase(DataCenterPhase.RUNNING);
                    endOperation("unparked");
                    return k8sResourceUtils.updateDataCenterStatus(dataCenter, dataCenterStatus).ignoreElement();
                });
    }

    public Completable scaleUpDatacenter(ConfigMapVolumeMounts configMapVolumeMounts) throws Exception {
        Completable todo = Completable.complete();
        Optional<Zone> scaleUpZone = zones.nextToScalueUp();
        if (!scaleUpZone.isPresent()) {
            logger.warn("datacenter={} Cannot scale up replicas={}/{}, no free node", dataCenter.id(), zones.totalReplicas(), dataCenter.getSpec().getReplicas());
            return todo;
        }

        // Scaling UP
        final Zone zone = scaleUpZone.get();
        logger.debug("Scaling UP zone={}", zone);
        if (!zone.getSts().isPresent()) {
            // create new sts with replicas = 1,
            Integer rackIndex = dataCenterStatus.getZones().indexOf(zone.name);
            RackStatus rackStatus = dataCenterStatus.getRackStatuses().computeIfAbsent(rackIndex, idx -> new RackStatus()
                    .setName(zone.name)
                    .setIndex(idx)
                    .setHealth(Health.RED)
                    .withDesiredReplicas(1)
                    .withFingerprint(configMapVolumeMounts.fingerPrint()));
            logger.debug("datacenter={} SCALE_UP started in rack={} rackIndex={} zones.size={}",
                    dataCenter.id(), rackStatus.getName(), rackStatus.getIndex(), zone.size);

            configMapVolumeMounts.setRack(rackStatus);
            return todo
                    .andThen(configMapVolumeMounts.createOrReplaceNamespacedConfigMaps())
                    .andThen(builder.buildStatefulSetRack(rackStatus, configMapVolumeMounts)
                            .flatMap(sts -> {
                                endOperation("scale-up rack index="+rackStatus.getIndex()+" name="+rackStatus.getName());
                                dataCenterStatus.setNeedCleanup(true);
                                return k8sResourceUtils.updateDataCenterStatus(dataCenter, dataCenterStatus);
                            })
                            .ignoreElement()
                    );
        }

        // +1 on sts replicas
        V1StatefulSet sts = zone.getSts().get();
        sts.getSpec().setReplicas(sts.getSpec().getReplicas() + 1);

        Integer rackIndex = Integer.parseInt(sts.getMetadata().getLabels().get(OperatorLabels.RACKINDEX));
        RackStatus rackStatus = dataCenterStatus.getRackStatuses().computeIfAbsent(rackIndex, idx -> new RackStatus()
                .setName(zone.name)
                .setIndex(idx)
                .setHealth(Health.RED)
                .withFingerprint(configMapVolumeMounts.fingerPrint()));
        rackStatus.setDesiredReplicas(sts.getSpec().getReplicas());
        logger.debug("datacenter={} SCALE_UP started in rack={} desiredReplicas={}", dataCenter.id(), rackStatus.getName(), rackStatus.getDesiredReplicas());

        // call ConfigMapVolumeMount here to update seeds in case of single rack with multi-nodes
        configMapVolumeMounts.setRack(rackStatus);
        return todo
                .andThen(configMapVolumeMounts.createOrReplaceNamespacedConfigMaps()) // update seeds
                .andThen(k8sResourceUtils.replaceNamespacedStatefulSet(sts)
                        .flatMap(s -> {
                            endOperation("scale-up rack="+rackStatus.getName());
                            dataCenterStatus.setNeedCleanup(true);
                            return k8sResourceUtils.updateDataCenterStatus(dataCenter, dataCenterStatus);
                        })
                        .ignoreElement());
    }

    public Completable scaleDownDatacenter(ConfigMapVolumeMounts configMapVolumeMounts, CqlSessionHandler cqlSessionHandler) throws Exception {
        Completable todo = Completable.complete();
        Optional<Zone> scaleDownZone = zones.nextToScaleDown();
        if (!scaleDownZone.isPresent()) {
            logger.warn("datacenter={} Cannot scale down, no more replicas", dataCenter.id(), dataCenterMetadata.getName(), dataCenterMetadata.getNamespace());
            return todo.toSingleDefault(dataCenterStatus).flatMapCompletable(dcs -> {
                endOperation("Cannot scale-down, no more replicas");
                return k8sResourceUtils.updateDataCenterStatus(dataCenter, dataCenterStatus).ignoreElement();
            });
        }

        // scaling DOWN
        Zone zone = scaleDownZone.get();
        V1StatefulSet sts = zone.getSts().get();
        Integer rackIndex = Integer.parseInt(sts.getMetadata().getLabels().get(OperatorLabels.RACKINDEX));
        RackStatus rackStatus = dataCenterStatus.getRackStatuses().get(rackIndex);

        if (dataCenterStatus.getBootstrapped() && dataCenterSpec.getReplicas() > 1) {
            todo = cqlKeyspaceManager.decreaseRfBeforeScalingDownDc(dataCenter, dataCenterStatus, zones.totalReplicas() - 1, cqlSessionHandler)
                    .andThen(Completable.fromAction(() -> {
                        // update the DC status after the decreaseRf because decreaseRf test DC phase is RUNNING...
                        logger.info("datacenter={} SCALE_DOWN started in rack={} size={}, decommissioning pod={}-{}",
                                dataCenter.id(), zone.name, zone.size, sts.getMetadata().getName(), sts.getSpec().getReplicas() - 1);
                    }))
                    .andThen(jmxmpElassandraProxy.decomission(new ElassandraPod(dataCenter, rackIndex, sts.getSpec().getReplicas() - 1))
                            .retryWhen(errors -> errors
                                    .zipWith(Flowable.range(1, 5), (n, i) -> i)
                                    .flatMap(retryCount -> Flowable.timer(2, TimeUnit.SECONDS))
                            ));
        }

        rackStatus.setDesiredReplicas(sts.getSpec().getReplicas() - 1);
        sts.getSpec().setReplicas(sts.getSpec().getReplicas() - 1);

        configMapVolumeMounts.setRack(rackStatus);
        return todo.andThen(configMapVolumeMounts.createOrReplaceNamespacedConfigMaps()) // update seeds
                .andThen(k8sResourceUtils.replaceNamespacedStatefulSet(sts).ignoreElement())
                .toSingleDefault(dataCenterSpec)
                .flatMapCompletable(s -> {
                    endOperation("scale-down");
                    return k8sResourceUtils.updateDataCenterStatus(dataCenter, dataCenterStatus).ignoreElement();
                });
    }

    public class ConfigMapVolumeMountBuilder {
        public final V1ConfigMap configMap;
        public final V1ConfigMapVolumeSource volumeSource;
        public final String mountName, mountPath;

        public ConfigMapVolumeMountBuilder(final V1ConfigMap configMap, final V1ConfigMapVolumeSource volumeSource, final String mountName, final String mountPath) {
            this.configMap = configMap;
            this.mountName = mountName;
            this.mountPath = mountPath;
            if (volumeSource != null) {
                // copy the volume source to avoid name change when makeUnique is call
                // otherwise the datacenterSpec fingerprint will change too
                this.volumeSource = new V1ConfigMapVolumeSource();
                this.volumeSource.setName(volumeSource.getName());
                if (volumeSource.getOptional() != null) {
                    this.volumeSource.setOptional(volumeSource.getOptional().booleanValue());
                }
                if (volumeSource.getDefaultMode() != null) {
                    this.volumeSource.setDefaultMode(volumeSource.getDefaultMode().intValue());
                }
                if (volumeSource.getItems() != null) {
                    List<V1KeyToPath> items = new ArrayList<>();
                    this.volumeSource.setItems(items);
                    for (V1KeyToPath src : volumeSource.getItems()) {
                        items.add(new V1KeyToPath()
                                .key(src.getKey())
                                .path(src.getPath())
                                .mode(src.getMode()));
                    }
                }
            } else {
                this.volumeSource = null;
            }
        }

        public String fingerPrint() {
            return configMapFingerPrint(configMap);
        }

        public ConfigMapVolumeMountBuilder makeUnique() {
            if (configMap != null) {
                String hashedName = OperatorNames.configMapUniqueName(configMap.getMetadata().getName(), fingerPrint());
                configMap.getMetadata().setName(hashedName);// TODO [ELE] fully copy the configmap to include the cofgimap into the dc fingerprint
                volumeSource.setName(hashedName);
            }
            return this;
        }

        public ConfigMapVolumeMountBuilder(final V1ConfigMapVolumeSource volumeSource, final String mountName, final String mountPath) {
            this(null, volumeSource, mountName, mountPath);
        }

        public Single<ConfigMapVolumeMountBuilder> createOrReplaceNamespacedConfigMap() throws ApiException {
            if (configMap != null) {
                return k8sResourceUtils.createOrReplaceNamespacedConfigMap(configMap).map(c -> this);
            }
            return Single.just(this);
        }

        public ConfigMapVolumeMountBuilder addFile(final String path, final String content) {
            final String encodedKey = path.replaceAll("\\W", "_");

            configMap.putDataItem(encodedKey, content);
            volumeSource.addItemsItem(new V1KeyToPath().key(encodedKey).path(path));
            return this;
        }

        public V1VolumeMount buildV1VolumeMount() {
            return new V1VolumeMount()
                    .name(mountName)
                    .mountPath(mountPath);
        }
    }


    class ConfigMapVolumeMounts implements Iterable<ConfigMapVolumeMountBuilder> {
        public ConfigMapVolumeMountBuilder seedConfig;  // per DC configmap, can be changed without triggering a rolling restart
        public ConfigMapVolumeMountBuilder rackConfig;  // per rack configmap

        public final ConfigMapVolumeMountBuilder operatorConfig;  // configmap generated from CRD
        public final Optional<ConfigMapVolumeMountBuilder> userConfig;  // user provided configmap

        public ConfigMapVolumeMounts(Optional<V1ConfigMap> userConfig) throws IOException, ApiException {
            this.operatorConfig = builder.buildConfigMapOperator();
            this.userConfig = builder.buildConfigMapUser(userConfig);
        }

        public void setRack(RackStatus rackStatus) throws IOException, ApiException {
            this.rackConfig = builder.buildConfigMapRack(rackStatus);
            this.seedConfig = builder.buildConfigMapSeed(zones);
        }

        public String fingerPrint() {
            String fingerprint = configMapFingerPrint(this.operatorConfig.configMap);
            if (userConfig.isPresent()) {
                fingerprint += "-" + configMapFingerPrint(userConfig.get().configMap);
            } else {
                fingerprint += "-0000000";
            }
            return fingerprint;
        }

        public Completable createOrReplaceNamespacedConfigMaps() throws ApiException {
            return operatorConfig.createOrReplaceNamespacedConfigMap().ignoreElement()
                    .andThen(rackConfig.createOrReplaceNamespacedConfigMap().ignoreElement())
                    .andThen(seedConfig.createOrReplaceNamespacedConfigMap().ignoreElement())
                    // use user configmap
                    .andThen(userConfig.isPresent() ?
                            userConfig.get().makeUnique().createOrReplaceNamespacedConfigMap().ignoreElement() :
                            Completable.complete()
                    );
        }

        /**
         * Returns an iterator over elements of type {@code T}.
         *
         * @return an Iterator.
         */
        @Override
        public Iterator<ConfigMapVolumeMountBuilder> iterator() {
            List<ConfigMapVolumeMountBuilder> builders = new ArrayList<>(4);
            builders.add(operatorConfig);
            builders.add(rackConfig);
            builders.add(seedConfig);
            if (userConfig.isPresent())
                builders.add(userConfig.get());
            return builders.iterator();
        }
    }

    /**
     * SHA1 first 7 caraters fingerprint of binaryData+data
     *
     * @return
     */
    public String configMapFingerPrint(V1ConfigMap configMap) {
        Map<String, Object> object = new HashMap<>(2);
        object.put("data", configMap.getData());
        if (configMap.getBinaryData() != null)
            object.put("binaryData", configMap.getBinaryData());
        return DigestUtils.sha1Hex(appsApi.getApiClient().getJSON().getGson().toJson(object)).substring(0, 7);
    }

    public class Builder {

        private String nodetoolSsl() {
            return "-Djavax.net.ssl.trustStore=" + authorityManager.getPublicCaMountPath() + "/" + AuthorityManager.SECRET_TRUSTSTORE_P12 + " " +
                    "-Djavax.net.ssl.trustStorePassword=" + authorityManager.getCaTrustPass() + " " +
                    "-Djavax.net.ssl.trustStoreType=PKCS12 " +
                    "-Dcom.sun.management.jmxremote.registry.ssl=true";
        }

        private boolean useJmxOverSSL() {
            return dataCenterSpec.getCassandra().getSsl() && (!dataCenterSpec.getJvm().getJmxmpEnabled() || (dataCenterSpec.getJvm().getJmxmpEnabled() && dataCenterSpec.getJvm().getJmxmpOverSSL()));
        }

        private void addPortsItem(V1Container container, int port, String name, boolean withHostPort) {
            if (port > 0) {
                V1ContainerPort v1Port = new V1ContainerPort().name(name).containerPort(port);
                container.addPortsItem((dataCenterSpec.getNetworking().getHostPortEnabled() && withHostPort) ? v1Port.hostPort(port) : v1Port);
            }
        }

        public V1ObjectMeta clusterObjectMeta(final String name) {
            return new V1ObjectMeta()
                    .name(name)
                    .namespace(dataCenterMetadata.getNamespace())
                    .addOwnerReferencesItem(OperatorNames.ownerReference(dataCenter))
                    .labels(OperatorLabels.cluster(dataCenterSpec.getClusterName()));
        }

        public V1ObjectMeta clusterObjectMeta(final String name, Map<String, String> additionalLabels) {
            return new V1ObjectMeta()
                    .name(name)
                    .namespace(dataCenterMetadata.getNamespace())
                    .labels(Stream.concat(
                            OperatorLabels.cluster(dataCenterSpec.getClusterName()).entrySet().stream(),
                            additionalLabels.entrySet().stream())
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        }

        private V1ObjectMeta dataCenterObjectMeta(final String name, String... labels) {
            assert labels.length % 2 == 0 : "labels arguments should be even";
            V1ObjectMeta meta = new V1ObjectMeta()
                    .name(name)
                    .namespace(dataCenterMetadata.getNamespace());
            for(Map.Entry<String, String> entry : OperatorLabels.datacenter(dataCenter).entrySet())
                meta.putLabelsItem(entry.getKey(), entry.getValue());
            for(int i = 0; i < labels.length; i+=2)
                meta.putLabelsItem(labels[i], labels[i+1]);
            meta.addOwnerReferencesItem(OperatorNames.ownerReference(dataCenter));
            meta.putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, dataCenter.getMetadata().getGeneration().toString());
            return meta;
        }

        private V1ObjectMeta rackObjectMeta(RackStatus rackStatus) {
            V1ObjectMeta meta = new V1ObjectMeta()
                    .name(OperatorNames.rackConfig(dataCenter, Integer.toString(rackStatus.getIndex())))
                    .namespace(dataCenterMetadata.getNamespace());
            for(Map.Entry<String, String> entry : OperatorLabels.rack(dataCenter, rackStatus.getName(), rackStatus.getIndex()).entrySet())
                meta.putLabelsItem(entry.getKey(), entry.getValue());
            meta.addOwnerReferencesItem(OperatorNames.ownerReference(dataCenter));
            meta.putAnnotationsItem(OperatorLabels.DATACENTER_FINGERPRINT, rackStatus.getFingerprint());
            meta.putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, dataCenter.getMetadata().getGeneration().toString());
            meta.putAnnotationsItem(OperatorLabels.PVC_DECOMMISSION_POLICY, dataCenterSpec.getDecommissionPolicy().getValue());
            return meta;
        }

        public String clusterChildObjectName(final String nameFormat) {
            return String.format(Locale.ROOT, nameFormat, "elassandra-" + dataCenter.getSpec().getClusterName());
        }

        public String dataCenterResource(final String clusterName, final String dataCenterName) {
            return "elassandra-" + clusterName + "-" + dataCenterName;
        }

        public String dataCenterChildObjectName(final String nameFormat) {
            return String.format(Locale.ROOT, nameFormat, dataCenterResource(dataCenterSpec.getClusterName(), dataCenter.getSpec().getDatacenterName()));
        }

        public String rackChildObjectName(final String nameFormat, final String rack) {
            return String.format(Locale.ROOT, nameFormat,
                    "elassandra-" + dataCenter.getSpec().getClusterName()
                            + "-" + dataCenter.getSpec().getDatacenterName()
                            + "-" + rack);
        }

        // see https://github.com/kubernetes-sigs/cloud-provider-azure/blob/master/docs/services/README.md
        // see https://github.com/kubernetes-sigs/external-dns
        // Unfortunately, AKS does not allow to reuse a public IP for multiple LB.
        public V1Service buildServiceExternal() throws ApiException {
            V1ServiceSpec v1ServiceSpec = new V1ServiceSpec()
                    .type(dataCenterSpec.getElasticsearch().getLoadBalancerEnabled() ? "LoadBalancer" : "ClusterIP")
                    .addPortsItem(new V1ServicePort().name("cql").port(dataCenterSpec.getCassandra().getNativePort()))
                    .addPortsItem(new V1ServicePort().name("elasticsearch").port(dataCenterSpec.getElasticsearch().getHttpPort()))
                    .selector(ImmutableMap.of(
                            OperatorLabels.CLUSTER, dataCenterSpec.getClusterName(),
                            OperatorLabels.DATACENTER, dataCenter.getSpec().getDatacenterName(),
                            OperatorLabels.APP, "elassandra"));

            V1ObjectMeta v1ObjectMeta = dataCenterObjectMeta(OperatorNames.externalService(dataCenter));

            // set loadbalancer public ip if available
            if (dataCenterSpec.getElasticsearch().getLoadBalancerEnabled()) {
                v1ServiceSpec.externalTrafficPolicy("Local");
                if (!Strings.isNullOrEmpty(dataCenterSpec.getElasticsearch().getLoadBalancerIp()))
                    v1ServiceSpec.setLoadBalancerIP(dataCenterSpec.getElasticsearch().getLoadBalancerIp());

                // Add external-dns annotation to update public DNS
                if (dataCenterSpec.getNetworking().getExternalDns() != null && dataCenterSpec.getNetworking().getExternalDns().getEnabled() == true) {
                    ExternalDns externalDns = dataCenterSpec.getNetworking().getExternalDns();
                    String elasticsearchHostname = "elasticsearch-" + externalDns.getRoot();
                    v1ObjectMeta.putAnnotationsItem("external-dns.alpha.kubernetes.io/hostname", elasticsearchHostname + "." + externalDns.getDomain());
                    if (externalDns.getTtl() != null && externalDns.getTtl() > 0)
                        v1ObjectMeta.putAnnotationsItem("external-dns.alpha.kubernetes.io/ttl", Integer.toString(externalDns.getTtl()));
                }
            }

            return new V1Service()
                    .metadata(v1ObjectMeta)
                    .spec(v1ServiceSpec);
        }

        // Build admin service for JMX only
        public V1Service buildServiceAdmin() throws ApiException {
            V1ServiceSpec v1ServiceSpec = new V1ServiceSpec()
                    .type("ClusterIP")
                    .addPortsItem(new V1ServicePort().name("jmx").port(dataCenterSpec.getJvm().getJmxPort()))
                    .selector(ImmutableMap.of(
                            OperatorLabels.CLUSTER, dataCenterSpec.getClusterName(),
                            OperatorLabels.DATACENTER, dataCenter.getSpec().getDatacenterName(),
                            OperatorLabels.APP, "elassandra"));

            V1ObjectMeta v1ObjectMeta = dataCenterObjectMeta(OperatorNames.adminService(dataCenter));

            return new V1Service()
                    .metadata(v1ObjectMeta)
                    .spec(v1ServiceSpec);
        }

        /**
         * Create a headless seed service per rack.
         *
         * @return
         * @throws ApiException
         */
        /*
        public V1Service buildServiceSeed(String rack) throws ApiException {
            final V1ObjectMeta serviceMetadata = dataCenterObjectMeta(OperatorNames.seedsService(dataCenter))
                    // tolerate-unready-endpoints - allow the seed provider can discover the other seeds (and itself) before the readiness-probe gives the green light
                    .putAnnotationsItem("service.alpha.kubernetes.io/tolerate-unready-endpoints", "true");

            return new V1Service()
                    .metadata(serviceMetadata)
                    .spec(new V1ServiceSpec()
                            .publishNotReadyAddresses(true)
                            .type("ClusterIP")
                            .clusterIP("None")
                            // a port needs to be defined for the service to be resolvable (#there-was-a-bug-ID-and-now-I-cant-find-it)
                            .ports(ImmutableList.of(
                                    new V1ServicePort().name("internode").port(dataCenterSpec.getSsl() ? dataCenterSpec.getSslStoragePort() : dataCenterSpec.getStoragePort()),
                                    new V1ServicePort().name("jmx").port(dataCenterSpec.getJmxPort()))
                            )
                            // only select the pod 0 in a rack as seed, which is not good for local DC discovery (if rack X is unavailable).
                            // We should use
                            .selector(OperatorLabels.rack(dataCenter, rack))
                    );
        }
        */


        public V1Service buildServiceNodes() {
            final V1ObjectMeta serviceMetadata = dataCenterObjectMeta(OperatorNames.nodesService(dataCenter))
                    .putAnnotationsItem("service.alpha.kubernetes.io/tolerate-unready-endpoints", "true");
            final V1Service service = new V1Service()
                    .metadata(serviceMetadata)
                    .spec(new V1ServiceSpec()
                            .publishNotReadyAddresses(true)
                            .type("ClusterIP")
                            .clusterIP("None")
                            .addPortsItem(new V1ServicePort().name("internode").port(dataCenterSpec.getCassandra().getSsl()
                                    ? dataCenterSpec.getCassandra().getSslStoragePort()
                                    : dataCenterSpec.getCassandra().getStoragePort()))
                            .addPortsItem(new V1ServicePort().name("cql").port(dataCenterSpec.getCassandra().getNativePort()))
                            .addPortsItem(new V1ServicePort().name("jmx").port(dataCenterSpec.getJvm().getJmxPort()))
                            .selector(OperatorLabels.datacenter(dataCenter))
                    );

            if (dataCenterSpec.getElasticsearch().getEnabled()) {
                service.getSpec().addPortsItem(new V1ServicePort().name(ELASTICSEARCH_PORT_NAME).port(dataCenterSpec.getElasticsearch().getHttpPort()));
            }

            if (dataCenterSpec.getPrometheus().getEnabled()) {
                service.getSpec().addPortsItem(new V1ServicePort().name(PROMETHEUS_PORT_NAME).port(dataCenterSpec.getPrometheus().getPort()));
            }
            return service;
        }

        public V1Service buildSingleNodeServiceLoadBalancer(String rackName, int rackIndex, int podIndex, String podName) {
            final V1ObjectMeta v1ObjectMeta = dataCenterObjectMeta(OperatorNames.nodesService(dataCenter))
                    .putAnnotationsItem("service.alpha.kubernetes.io/tolerate-unready-endpoints", "true");

            // Add external-dns annotation to update public DNS allowing nodes to get there broadcast address
            if (dataCenterSpec.getNetworking().getExternalDns() != null && dataCenterSpec.getNetworking().getExternalDns().getEnabled() == true) {
                ExternalDns externalDns = dataCenterSpec.getNetworking().getExternalDns();
                String nodeHostname = "cassandra-" + externalDns.getRoot() + "-" +rackIndex + "-" +podIndex;
                v1ObjectMeta.putAnnotationsItem("external-dns.alpha.kubernetes.io/hostname", nodeHostname + "." + externalDns.getDomain());
                if (externalDns.getTtl() != null && externalDns.getTtl() > 0)
                    v1ObjectMeta.putAnnotationsItem("external-dns.alpha.kubernetes.io/ttl", Integer.toString(externalDns.getTtl()));
            }
            final V1Service service = new V1Service()
                    .metadata(v1ObjectMeta)
                    .spec(new V1ServiceSpec()
                            .type("LoadBalancer")
                            .publishNotReadyAddresses(true)
                            .addPortsItem(new V1ServicePort().name("internode").port(dataCenterSpec.getCassandra().getSsl()
                                    ? dataCenterSpec.getCassandra().getSslStoragePort()
                                    : dataCenterSpec.getCassandra().getStoragePort()))
                            .addPortsItem(new V1ServicePort().name("cql").port(dataCenterSpec.getCassandra().getNativePort()))
                            .selector(OperatorLabels.pod(dataCenter, rackName, rackIndex, podName))
                    );
            return service;
        }

        public V1Service buildServiceElasticsearch() {
            return new V1Service()
                    .metadata(dataCenterObjectMeta(OperatorNames.elasticsearchService(dataCenter)))
                    .spec(new V1ServiceSpec()
                            .type("ClusterIP")
                            .addPortsItem(new V1ServicePort().name(ELASTICSEARCH_PORT_NAME).port(dataCenterSpec.getElasticsearch().getHttpPort()))
                            .selector(OperatorLabels.datacenter(dataCenter))
                    );
        }

        // Add Ingress on Elasticsearch, but break TLS and basic auth
        /*
        public Optional<V1beta1Ingress> buildIngressElasticsearch() {
            Optional<V1beta1Ingress> ingress = Optional.empty();
            String ingressDomain = System.getenv("INGRESS_DOMAIN");
            if (dataCenterSpec.getElasticsearchEnabled() && dataCenterSpec.getElasticsearchIngressEnabled() && !Strings.isNullOrEmpty(ingressDomain)) {
                String serviceName = OperatorNames.elasticsearchService(dataCenter);
                String elasticHost = serviceName + "-" + ingressDomain.replace("${namespace}", dataCenterMetadata.getNamespace());
                logger.info("Creating elasticsearch ingress for host={}", elasticHost);
                V1ObjectMeta meta = dataCenterObjectMeta(serviceName);

                Map<String, String> annotations = new HashMap<>();
                // preserve client host for elasticsearch audit, see https://docs.traefik.io/v1.7/configuration/backends/kubernetes/#annotations
                annotations.put("traefik.ingress.kubernetes.io/preserve-host", "true");
                // Add annotation to connect in https, see https://docs.traefik.io/v1.7/configuration/backends/kubernetes/
                if (dataCenterSpec.getEnterprise().getHttps())
                    annotations.put("ingress.kubernetes.io/protocol", "https");
                meta.setAnnotations(annotations);

                ingress = Optional.of(new V1beta1Ingress()
                        .metadata(meta)
                        .spec(new V1beta1IngressSpec()
                                .addRulesItem(new V1beta1IngressRule()
                                        .host(elasticHost)
                                        .http(new V1beta1HTTPIngressRuleValue()
                                                .addPathsItem(new V1beta1HTTPIngressPath()
                                                        .path("/")
                                                        .backend(new V1beta1IngressBackend()
                                                                .serviceName(meta.getName())
                                                                .servicePort(new IntOrString(dataCenterSpec.getElasticsearchPort())))
                                                )
                                        )
                                )
                                .addTlsItem(new V1beta1IngressTLS()
                                        .addHostsItem(elasticHost)
                                )
                        ));
            }
            return ingress;
        }
        */

        /**
         * Mutable configmap for seeds, one for all racks, does not require a rolling restart.
         *
         * @return
         */
        public ConfigMapVolumeMountBuilder buildConfigMapSeed(Zones zones) {
            final V1ConfigMap configMap = new V1ConfigMap()
                    .metadata(dataCenterObjectMeta(OperatorNames.seedConfig(dataCenter)));

            final V1ConfigMapVolumeSource volumeSource = new V1ConfigMapVolumeSource().name(configMap.getMetadata().getName());

            // cassandra.yaml overrides
            Set<String> remoteSeeds = new HashSet<>();
            if (dataCenterSpec.getCassandra().getRemoteSeeds() != null && !dataCenterSpec.getCassandra().getRemoteSeeds().isEmpty()) {
                remoteSeeds.addAll(dataCenterSpec.getCassandra().getRemoteSeeds().stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList()));
            }

            Set<String> remoteSeeders = new HashSet<>();
            if (dataCenterSpec.getCassandra().getRemoteSeeders() != null && !dataCenterSpec.getCassandra().getRemoteSeeders().isEmpty()) {
                remoteSeeders.addAll(dataCenterSpec.getCassandra().getRemoteSeeders().stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList()));
            }
            if (sibilingDcs != null && sibilingDcs.isEmpty() == false) {
                for(String sibilingDc : sibilingDcs) {
                    remoteSeeders.add(String.format(Locale.ROOT, "https://%s.%s.svc/seeds/%s/%s/%s",
                            operatorConfig.getServiceName(),
                            operatorConfig.getOperatorNamespace(),
                            dataCenterMetadata.getNamespace(),
                            dataCenterSpec.getClusterName(),
                            sibilingDc));
                }
            }

            Set<String> seeds = new HashSet<>();
            if (dataCenterStatus.getBootstrapped() == false) {
                if (remoteSeeds.isEmpty() && remoteSeeders.isEmpty()) {
                    // first node in the first DC is seed
                    RackStatus rackStatus = dataCenterStatus.getRackStatuses().values().stream()
                            .filter(r -> r.getIndex() == 0).collect(Collectors.toList()).get(0);
                    if ((dataCenterSpec.getNetworking().getHostNetworkEnabled() || dataCenterSpec.getNetworking().getHostPortEnabled()) &&
                            dataCenterSpec.getNetworking().getExternalDns().getEnabled()) {
                        seeds.add(OperatorNames.externalPodFqdn(dataCenter, rackStatus.getIndex(), 0));
                    } else {
                        seeds.add(OperatorNames.internalPodFqdn(dataCenter, rackStatus.getIndex(), 0));
                    }
                } else {
                    // first node, second DC => use remote seeds to stream.
                }
            } else {
                // node-0 of each rack are seeds
                for (RackStatus rackStatus : dataCenterStatus.getRackStatuses().values()) {
                    if (dataCenterSpec.getNetworking().getHostNetworkEnabled() || dataCenterSpec.getNetworking().getHostPortEnabled()) {
                        seeds.add(OperatorNames.externalPodFqdn(dataCenter, rackStatus.getIndex(), 0));
                    } else {
                        seeds.add(OperatorNames.internalPodFqdn(dataCenter, rackStatus.getIndex(), 0));
                    }
                }
            }

            if (dataCenterStatus.getBootstrapped() == true || (remoteSeeds.isEmpty() && remoteSeeders.isEmpty())) {
                // Add local seeds if DC is boostrapped or alone.
                for (RackStatus rackStatus : dataCenterStatus.getRackStatuses().values()) {
                    if (rackStatus.getReadyReplicas() != null && rackStatus.getReadyReplicas() > 0) {
                        if (dataCenterSpec.getNetworking().getHostNetworkEnabled() || dataCenterSpec.getNetworking().getHostPortEnabled()) {
                            seeds.add(OperatorNames.externalPodFqdn(dataCenter, rackStatus.getIndex(), 0));
                        } else {
                            seeds.add(OperatorNames.internalPodFqdn(dataCenter, rackStatus.getIndex(), 0));
                        }
                    }
                }
            }

            Map<String, String> parameters = new HashMap<>();
            if (!seeds.isEmpty())
                parameters.put("seeds", String.join(", ", seeds));
            if (!remoteSeeds.isEmpty())
                parameters.put("remote_seeds", String.join(", ", remoteSeeds));
            if (!remoteSeeders.isEmpty()) {
                parameters.put("remote_seeders", String.join(", ", remoteSeeders));
            }

            logger.debug("seed parameters={}", parameters);
            final Map<String, Object> config = new HashMap<>(); // can't use ImmutableMap as some values are null
            config.put("seed_provider", ImmutableList.of(ImmutableMap.of(
                    "class_name", ElassandraOperatorSeedProvider.class.getName(),
                    "parameters", ImmutableList.of(parameters))
            ));
            // if datacenter is not bootstrapped, add nodes with auto_bootstrap = false
            if ((!remoteSeeds.isEmpty() || !remoteSeeders.isEmpty()) && dataCenterStatus.getBootstrapped() == false) {
                // second DC will be rebuild from another one
                config.put("auto_bootstrap", false);
            } else {
                // first DC is considered as boostrapped
                dataCenterStatus.setBootstrapped(true);
            }
            return new ConfigMapVolumeMountBuilder(configMap, volumeSource, "operator-config-volume-seeds", "/tmp/operator-config-seeds")
                    .addFile("cassandra.yaml.d/001-cassandra-seeds.yaml", toYamlString(config));
        }

        /**
         * One configmap per sts, mutable and suffixed by a hash of the spec data
         *
         * @return
         * @throws IOException
         */
        public ConfigMapVolumeMountBuilder buildConfigMapOperator() throws IOException {
            final V1ConfigMap configMap = new V1ConfigMap().metadata(dataCenterObjectMeta(OperatorNames.operatorConfig(dataCenter)));
            final V1ConfigMapVolumeSource volumeSource = new V1ConfigMapVolumeSource().name(configMap.getMetadata().getName());
            final ConfigMapVolumeMountBuilder configMapVolumeMountBuilder =
                    new ConfigMapVolumeMountBuilder(configMap, volumeSource, "operator-config-volume", "/tmp/operator-config");

            // cassandra.yaml overrides
            {
                final Map<String, Object> config = new HashMap<>(); // can't use ImmutableMap as some values are null

                config.put("cluster_name", dataCenterSpec.getClusterName());
                config.put("listen_address", null); // let C* discover the listen address
                // broadcast_rpc is set dynamically from entry-point.sh according to env $POD_IP
                config.put("rpc_address", "0.0.0.0"); // bind rpc to all addresses (allow localhost access)

                config.put("storage_port", dataCenterSpec.getCassandra().getStoragePort());
                config.put("ssl_storage_port", dataCenterSpec.getCassandra().getSslStoragePort());
                config.put("native_transport_port", dataCenterSpec.getCassandra().getNativePort());

                // disable dynamic snitch for better stability,
                config.put("dynamic_snitch", false);

                // compute the number os CPU to adapt the ConcurrentWriter settings (force a min to 1 to avoid the a ThreadPool of 0)
                final int cpu = Math.max(1, Runtime.getRuntime().availableProcessors());
                // writer recommendation is 8 * CPUs
                final int concurrentWriter = cpu * 8;
                config.put("concurrent_writes", concurrentWriter);
                config.put("concurrent_materialized_view_writes", concurrentWriter);

                // reader recommendation is based on the number of disk (nbDisk * 16)
                // so leave the default value or set to nb of writer if writers are more than 32...
                final int concurrentReader = Math.max(32, concurrentWriter);
                config.put("concurrent_reads", concurrentReader);
                // counter use the same as reader because counter read the value before increment & write value
                config.put("concurrent_counter_writes", concurrentReader);

                // value used by : https://blog.deimos.fr/2018/06/24/running-cassandra-on-kubernetes/
                config.put("hinted_handoff_throttle_in_kb", 4096);

                if (dataCenterSpec.getCassandra().getWorkload() != null) {
                    if (dataCenterSpec.getCassandra().getWorkload().equals(Workload.READ)
                            || dataCenterSpec.getCassandra().getWorkload().equals(Workload.READ_WRITE)) {
                        // because we are in a read heavy workload, we set the cache to 100MB
                        // (the max value of the auto setting -  (min(5% of Heap (in MB), 100MB)) )
                        config.put("key_cache_size_in_mb", 100);
                    }

                    if (dataCenterSpec.getCassandra().getWorkload().equals(Workload.WRITE)
                            || dataCenterSpec.getCassandra().getWorkload().equals(Workload.READ_WRITE)) {
                        // configure memtable_flush_writers has an influence on the memtable_cleanup_threshold (1/(nb_flush_w + 1))
                        // so we set a little bit higher value for Write Heavy Workload
                        // default is 1/(memtable_flush_writers +1) ==> 1/3
                        // increase the number of memtable flush, increase the frequncy of memtable flush
                        // cpu = 1 ==> 1
                        // cpu = 2 ==> 2
                        // cpu = 4 ==> 2
                        // cpu = 8 ==> 4
                        // cpu = 16 ==> 8
                        final int flusher = Math.min(cpu, Math.max(2, cpu / 2));
                        config.put("memtable_flush_writers", flusher);

                        // https://tobert.github.io/pages/als-cassandra-21-tuning-guide.html
                        // Offheap memtables can improve write-heavy workloads by reducing the amount of data stored on the Java heap
                        config.put("memtable_allocation_type", "offheap_objects");
                    }

                    if (dataCenterSpec.getCassandra().getWorkload().equals(Workload.READ_WRITE)) {
                        // The faster you insert data, the faster you need to compact in order to keep the sstable count down,
                        // but in general, setting this to 16 to 32 times the rate you are inserting data is more than sufficient.
                        config.put("compaction_throughput_mb_per_sec", 24); // default is 16 - set to 24 to increase the compaction speed
                    }
                }

                configMapVolumeMountBuilder.addFile("cassandra.yaml.d/001-operator.yaml", toYamlString(config));
            }

            // prometheus support (see prometheus annotations)
            if (dataCenterSpec.getPrometheus().getEnabled()) {
                // jmx-promtheus exporter
                // TODO: use version less symlink to avoid issues when upgrading the image
                configMapVolumeMountBuilder.addFile("cassandra-env.sh.d/001-cassandra-exporter.sh",
                        "JVM_OPTS=\"${JVM_OPTS} -javaagent:${CASSANDRA_HOME}/agents/jmx_prometheus_javaagent.jar=${POD_IP}:" +
                                dataCenterSpec.getPrometheus().getPort() +
                                ":${CASSANDRA_CONF}/jmx_prometheus_exporter.yml\"");
            }

            StringBuilder jvmOptionsD = new StringBuilder(500);
            jvmOptionsD.append("-Dcassandra.jmx.remote.port=" + dataCenterSpec.getJvm().getJmxPort() + "\n");

            // Add JMX configuration
            if (dataCenterSpec.getJvm().getJmxmpEnabled()) {
                // JMXMP is fine, but visualVM cannot use jmxmp+tls+auth
                jvmOptionsD.append("-Dcassandra.jmxmp=true\n");
            }

            // Remote JMX require SSL, otherwise this is local clear JMX
            if (useJmxOverSSL()) {
                jvmOptionsD.append("-Dcom.sun.management.jmxremote.rmi.port=" + dataCenterSpec.getJvm().getJmxPort() + "\n");
                jvmOptionsD.append("-Dcom.sun.management.jmxremote.authenticate=true\n");
                jvmOptionsD.append("-Dcom.sun.management.jmxremote.password.file=/etc/cassandra/jmxremote.password\n");
                //"-Dcom.sun.management.jmxremote.access.file=/etc/cassandra/jmxremote.access\n" + \
                jvmOptionsD.append("-Dcom.sun.management.jmxremote.ssl=true\n");
                jvmOptionsD.append("-Dcom.sun.management.jmxremote.registry.ssl=true\n");
                jvmOptionsD.append("-Djavax.net.ssl.keyStore=" + OPERATOR_KEYSTORE_MOUNT_PATH + "/" + OPERATOR_KEYSTORE + "\n");
                jvmOptionsD.append("-Djavax.net.ssl.keyStorePassword=" + OPERATOR_KEYPASS + "\n");
                jvmOptionsD.append("-Djavax.net.ssl.keyStoreType=PKCS12\n");
                jvmOptionsD.append("-Djavax.net.ssl.trustStore=" + authorityManager.getPublicCaMountPath() + "/" + AuthorityManager.SECRET_TRUSTSTORE_P12 + "\n");
                jvmOptionsD.append("-Djavax.net.ssl.trustStorePassword=" + authorityManager.getCaTrustPass() + "\n");
                jvmOptionsD.append("-Djavax.net.ssl.trustStoreType=PKCS12");
            } else {
                // local JMX, clear + no auth
                jvmOptionsD.append("-Dcom.sun.management.jmxremote.rmi.port=" + dataCenterSpec.getJvm().getJmxPort() + "\n");
                jvmOptionsD.append("-Dcom.sun.management.jmxremote.authenticate=true\n");
                jvmOptionsD.append("-Dcom.sun.management.jmxremote.password.file=/etc/cassandra/jmxremote.password\n");
                jvmOptionsD.append("-Djava.rmi.server.hostname=127.0.0.1\n");
                jvmOptionsD.append("-XX:+DisableAttachMechanism");
            }
            configMapVolumeMountBuilder.addFile("jvm.options.d/001-jmx.options", jvmOptionsD.toString());

            // Add jdb transport socket
            if (dataCenterSpec.getJvm().getJdbPort() > 0) {
                configMapVolumeMountBuilder.addFile("cassandra-env.sh.d/001-cassandra-jdb.sh",
                        "JVM_OPTS=\"${JVM_OPTS} -Xdebug -Xnoagent -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=${POD_IP}:" + dataCenterSpec.getJvm().getJdbPort() + "\"");
            }

            // heap size and GC settings
            if (dataCenterSpec.getJvm().isComputeJvmMemorySettings() && dataCenterSpec.getResources() != null) {
                Map<String, Quantity> resourceQuantity = Optional.ofNullable(dataCenterSpec.getResources().getRequests()).orElse(dataCenterSpec.getResources().getLimits());
                final long memoryLimit = QuantityConverter.toMegaBytes(resourceQuantity.get("memory"));
                final long coreCount = QuantityConverter.toCpu(resourceQuantity.get("cpu"));

                // same as stock cassandra-env.sh
                final double jvmHeapSizeInMb = Math.max(
                        Math.min(memoryLimit / 2, 1.5 * 1024),
                        Math.min(memoryLimit / 4, 8 * 1024)
                );

                final double youngGenSizeInMb = Math.min(
                        100 * coreCount,
                        jvmHeapSizeInMb / 4
                );

                logger.debug("cluster={} dc={} namespace={} memoryLimit={} cpuLimit={} coreCount={} jvmHeapSizeInMb={} youngGenSizeInMb={}",
                        dataCenterSpec.getClusterName(), dataCenterSpec.getDatacenterName(), dataCenterMetadata.getNamespace(),
                        memoryLimit, resourceQuantity.get("cpu").getNumber(), coreCount, jvmHeapSizeInMb, youngGenSizeInMb);

                if (jvmHeapSizeInMb < 1.2 * 1024) {
                    logger.warn("Cannot deploy elassandra with less than 1.2Gb heap, please increase your kubernetes memory limits if you are in production environment");
                }

                final boolean useG1GC = (jvmHeapSizeInMb > 12 * 1024);
                //final StringWriter writer = new StringWriter();
                StringBuilder jvmGCOptions = new StringBuilder(500);

                jvmGCOptions.append(String.format(Locale.ROOT, "-Xms%dm", (long) jvmHeapSizeInMb) + "\n"); // min heap size
                jvmGCOptions.append(String.format(Locale.ROOT, "-Xmx%dm", (long) jvmHeapSizeInMb) + "\n"); // max heap size

                // copied from stock jvm.options
                if (useG1GC) {
                    jvmGCOptions.append("-XX:+UseG1GC\n");
                    jvmGCOptions.append("-XX:G1RSetUpdatingPauseTimePercent=5\n");
                    jvmGCOptions.append("-XX:MaxGCPauseMillis=500\n");

                    if (jvmHeapSizeInMb > 12 * 1024) {
                        jvmGCOptions.append("-XX:InitiatingHeapOccupancyPercent=70\n");
                    }

                    // TODO: tune -XX:ParallelGCThreads, -XX:ConcGCThreads
                } else {
                    jvmGCOptions.append(String.format(Locale.ROOT, "-Xmn%dm", (long) youngGenSizeInMb) + "\n"); // young gen size
                    jvmGCOptions.append("-XX:+UseParNewGC\n");
                    jvmGCOptions.append("-XX:+UseConcMarkSweepGC\n");
                    jvmGCOptions.append("-XX:+CMSParallelRemarkEnabled\n");
                    jvmGCOptions.append("-XX:SurvivorRatio=8\n");
                    jvmGCOptions.append("-XX:MaxTenuringThreshold=1\n");
                    jvmGCOptions.append("-XX:CMSInitiatingOccupancyFraction=75\n");
                    jvmGCOptions.append("-XX:+UseCMSInitiatingOccupancyOnly\n");
                    jvmGCOptions.append("-XX:CMSWaitDuration=10000\n");
                    jvmGCOptions.append("-XX:+CMSParallelInitialMarkEnabled\n");
                    jvmGCOptions.append("-XX:+CMSEdenChunksRecordAlways\n");
                    jvmGCOptions.append("-XX:+CMSClassUnloadingEnabled\n");
                }
                configMapVolumeMountBuilder.addFile("jvm.options.d/001-jvm-memory-gc.options", jvmGCOptions.toString());
            }


            // TODO: maybe tune -Dcassandra.available_processors=number_of_processors - Wait till we build C* for Java 11
            // not sure if k8s exposes the right number of CPU cores inside the container

            // add SSL config
            if (dataCenterSpec.getCassandra().getSsl()) {
                final Map<String, Object> cassandraConfig = new HashMap<>();
                cassandraConfig.put("server_encryption_options", ImmutableMap.builder()
                        .put("internode_encryption", "all")
                        .put("keystore", OPERATOR_KEYSTORE_MOUNT_PATH + "/" + OPERATOR_KEYSTORE)
                        .put("keystore_password", OPERATOR_KEYPASS)
                        .put("truststore", authorityManager.getPublicCaMountPath() + "/" + AuthorityManager.SECRET_TRUSTSTORE_P12)
                        .put("truststore_password", authorityManager.getCaTrustPass())
                        .put("protocol", "TLSv1.2")
                        .put("algorithm", "SunX509")
                        .put("store_type", "PKCS12")
                        //.put("cipher_suites", ImmutableList.of("TLS_RSA_WITH_AES_256_CBC_SHA"))
                        .put("require_client_auth", true)
                        .build()
                );
                cassandraConfig.put("client_encryption_options", ImmutableMap.builder()
                        .put("enabled", true)
                        .put("keystore", OPERATOR_KEYSTORE_MOUNT_PATH + "/" + OPERATOR_KEYSTORE)
                        .put("keystore_password", OPERATOR_KEYPASS)
                        .put("truststore", authorityManager.getPublicCaMountPath() + "/" + AuthorityManager.SECRET_TRUSTSTORE_P12)
                        .put("truststore_password", authorityManager.getCaTrustPass())
                        .put("protocol", "TLSv1.2")
                        .put("store_type", "PKCS12")
                        .put("algorithm", "SunX509")
                        .put("require_client_auth", false)
                        //.put("cipher_suites", ImmutableList.of("TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_256_CBC_SHA"))
                        .build()
                );
                configMapVolumeMountBuilder.addFile("cassandra.yaml.d/001-ssl.yaml", toYamlString(cassandraConfig));
            }

            // add authentication config
            switch (dataCenterSpec.getCassandra().getAuthentication()) {
                case NONE:
                    // create configMapFile also in NONE case because the ElassandraEnterprise image
                    // defines the PasswordAuthorizer as default.
                    configMapVolumeMountBuilder.addFile("cassandra.yaml.d/001-authentication.yaml",
                            toYamlString(ImmutableMap.of(
                                    "authenticator", "AllowAllAuthenticator",
                                    "authorizer", "AllowAllAuthorizer")));
                    break;
                case CASSANDRA:
                    configMapVolumeMountBuilder.addFile("cassandra.yaml.d/001-authentication.yaml",
                            toYamlString(ImmutableMap.of(
                                    "authenticator", "PasswordAuthenticator",
                                    "authorizer", "CassandraAuthorizer",
                                    "roles_validity_in_ms","3000",
                                    "permissions_validity_in_ms","3000",
                                    "credentials_validity_in_ms","3000")));
                    break;
                case LDAP:
                    configMapVolumeMountBuilder.addFile("cassandra.yaml.d/001-authentication.yaml",
                            toYamlString(ImmutableMap.of(
                                    "authenticator", "com.strapdata.cassandra.ldap.LDAPAuthenticator",
                                    "authorizer", "CassandraAuthorizer",
                                    "role_manager", "com.strapdata.cassandra.ldap.LDAPRoleManager")));
                    //TODO: Add ldap.properties + ldap.pem +
                    // -Dldap.properties.file=/usr/share/cassandra/conf/ldap.properties
                    // -Dcom.sun.jndi.ldap.object.disableEndpointIdentification=true"
                    break;
            }

            // add elasticsearch config support
            if (dataCenterSpec.getElasticsearch().getEnabled()) {
                final Enterprise enterprise = dataCenterSpec.getElasticsearch().getEnterprise();
                if (enterprise.getEnabled()) {
                    final Map<String, Object> esConfig = new HashMap<>();
                    esConfig.put("http.port", dataCenterSpec.getElasticsearch().getHttpPort());

                    esConfig.put("jmx", ImmutableMap.of("enabled", enterprise.getJmx()));
                    esConfig.put("https", ImmutableMap.of("enabled", enterprise.getHttps()));
                    esConfig.put("ssl", ImmutableMap.of("transport", ImmutableMap.of("enabled", enterprise.getSsl())));

                    if (enterprise.getAaa() == null) {
                        esConfig.put("aaa", ImmutableMap.of("enabled", false));
                    } else {
                        esConfig.put("aaa", ImmutableMap.of(
                                "enabled", enterprise.getAaa().getEnabled(),
                                "audit", ImmutableMap.of("enabled", enterprise.getAaa().getAudit())
                        ));
                    }

                    esConfig.put("cbs", ImmutableMap.of("enabled", enterprise.getCbs()));
                    configMapVolumeMountBuilder.addFile("elasticsearch.yml.d/001-enterprise.yaml", toYamlString(esConfig));
                    configMapVolumeMountBuilder.addFile("cassandra-env.sh.d/001-enterprise.sh",
                            "JVM_OPTS=\"$JVM_OPTS -Dcassandra.custom_query_handler_class=org.elassandra.index.EnterpriseElasticQueryHandler \"");
                    // TODO: override com exporter in cassandra-env.sh.d/001-cassandra-exporter.sh
                }

                // add elassandra datacenter.group config
                if (dataCenterSpec.getElasticsearch().getDatacenterGroup() != null) {
                    final Map<String, Object> esConfig = new HashMap<>();
                    esConfig.put("datacenter", ImmutableMap.of("group", dataCenterSpec.getElasticsearch().getDatacenterGroup()));
                    configMapVolumeMountBuilder.addFile("elasticsearch.yml.d/001-datacentergroup.yaml", toYamlString(esConfig));
                }
            }

            return configMapVolumeMountBuilder.makeUnique();
        }

        /**
         * configuration that is specific to rack. For the moment, an update of it does not trigger a restart
         * One immutable configmap per rack
         */
        private ConfigMapVolumeMountBuilder buildConfigMapRack(final RackStatus rackStatus) throws IOException {
            final V1ConfigMap configMap = new V1ConfigMap().metadata(rackObjectMeta(rackStatus));
            final V1ConfigMapVolumeSource volumeSource = new V1ConfigMapVolumeSource().name(configMap.getMetadata().getName());

            // GossipingPropertyFileSnitch config
            final Properties rackDcProperties = new Properties();
            rackDcProperties.setProperty("dc", dataCenterSpec.getDatacenterName());
            rackDcProperties.setProperty("rack", rackStatus.getName());
            rackDcProperties.setProperty("prefer_local", Boolean.toString(dataCenterSpec.getCassandra().getSnitchPreferLocal()));

            final StringWriter writer = new StringWriter();
            rackDcProperties.store(writer, "generated by cassandra-operator");
            // Set default Dc:rack in cassandra-topology.properties to avoid inconsistent nodetool status when a node is down.
            // This is because GossipingPropertyFileSnitch inherits from PropertyFileSnitch
            return new ConfigMapVolumeMountBuilder(configMap, volumeSource, "operator-config-volume-rack", "/tmp/operator-config-rack")
                    .addFile("cassandra-rackdc.properties", writer.toString())
                    .addFile("cassandra-topology.properties", String.format(Locale.ROOT, "default=%s:%s", dataCenterSpec.getDatacenterName(), rackStatus.getName()));
        }

        public Optional<ConfigMapVolumeMountBuilder> buildConfigMapUser(Optional<V1ConfigMap> userConfigMap) {
            return userConfigMap.map(configMap -> {
                        V1ObjectMeta meta = new V1ObjectMeta()
                                .name(configMap.getMetadata().getName())
                                .namespace(configMap.getMetadata().getNamespace())
                                .annotations(configMap.getMetadata().getAnnotations())
                                .labels(configMap.getMetadata().getLabels())
                                .addOwnerReferencesItem(OperatorNames.ownerReference(dataCenter));
                        V1ConfigMap configMap1 = new V1ConfigMap()
                                .metadata(meta)
                                .data(configMap.getData())
                                .binaryData(configMap.getBinaryData());
                        return new ConfigMapVolumeMountBuilder(configMap1, dataCenterSpec.getUserConfigMapVolumeSource(), "user-config-volume", "/tmp/user-config");
                    });
        }


        public V1Secret buildSecretKeystore(X509CertificateAndPrivateKey x509CertificateAndPrivateKey) throws GeneralSecurityException, IOException, OperatorCreationException {
            final V1ObjectMeta certificatesMetadata = dataCenterObjectMeta(OperatorNames.keystoreSecret(dataCenter),
                    OperatorLabels.KEYSTORE, Boolean.TRUE.toString());

            // generate statefulset wildcard certificate in a PKCS12 keystore
            final String wildcardStatefulsetName = "*." + OperatorNames.nodesService(dataCenter) + "." + dataCenterMetadata.getNamespace() + ".svc";

            List<String> dnsNames = new ArrayList<>();
            if (dataCenterSpec.getNetworking().getExternalDns() != null && dataCenterSpec.getNetworking().getExternalDns().getEnabled() && dataCenterSpec.getNetworking().getExternalDns().getDomain() != null) {
                dnsNames.add("*." + dataCenterSpec.getNetworking().getExternalDns().getDomain());
                dnsNames.add(OperatorNames.elasticsearchService(dataCenter) + "." + dataCenterMetadata.getNamespace()+ ".svc");
            } else {
                final String headlessServiceName = OperatorNames.nodesService(dataCenter) + "." + dataCenterMetadata.getNamespace() + ".svc";
                final String elasticsearchServiceName = OperatorNames.elasticsearchService(dataCenter) + "." + dataCenterMetadata.getNamespace() + ".svc";
                dnsNames.add(wildcardStatefulsetName);
                dnsNames.add(headlessServiceName);
                dnsNames.add(elasticsearchServiceName);
            }
            dnsNames.add("localhost");

            @SuppressWarnings("UnstableApiUsage") final V1Secret certificatesSecret = new V1Secret()
                    .metadata(certificatesMetadata)
                    .type("Opaque")
                    .putDataItem("keystore.p12",
                            authorityManager.issueCertificateKeystore(
                                    x509CertificateAndPrivateKey,
                                    wildcardStatefulsetName,
                                    dnsNames,
                                    ImmutableList.of(InetAddresses.forString("127.0.0.1")),
                                    dataCenterMetadata.getName(),
                                    "changeit"
                            ))
                    // add a client certificate in a PKCS12 keystore for TLS client auth
                    .putDataItem("client-keystore.p12",
                            authorityManager.issueCertificateKeystore(
                                    x509CertificateAndPrivateKey,
                                    "client",
                                    ImmutableList.of(),
                                    ImmutableList.of(),
                                    dataCenterMetadata.getName(),
                                    "changeit"
                            ));

            return certificatesSecret;
        }

        /**
         * Generate rc files as secret (.curlrc and .cassandra/cqlshrc + .cassandra/nodetool-ssl.properties)
         * TODO: avoid generation on each reconciliation
         *
         * @param username
         * @param password
         */
        public V1Secret buildSecretRcFile(String username, String password) {
            String cqlshrc = "";
            String curlrc = "";

            if (dataCenterSpec.getCassandra().getSsl()) {
                cqlshrc += "[connection]\n" +
                        "factory = cqlshlib.ssl.ssl_transport_factory\n" +
                        "port = " + dataCenterSpec.getCassandra().getNativePort() + "\n" +
                        "ssl = true\n" +
                        "\n" +
                        "[ssl]\n" +
                        "certfile = " + authorityManager.getPublicCaMountPath() + "/cacert.pem\n" +
                        "validate = true\n";

                if (dataCenterSpec.getElasticsearch().getEnterprise().getSsl()) {
                    curlrc += "cacert = " + authorityManager.getPublicCaMountPath() + "/cacert.pem\n";
                }
            } else {
                cqlshrc += "[connection]\n" +
                        "factory = cqlshlib.ssl.ssl_transport_factory\n" +
                        "port = " + dataCenterSpec.getCassandra().getNativePort() + "\n";
            }

            if (!dataCenterSpec.getCassandra().getAuthentication().equals(Authentication.NONE)) {
                cqlshrc += String.format(Locale.ROOT, "[authentication]\n" +
                        "username = %s\n" +
                        "password = %s", username, password);
                if (dataCenterSpec.getElasticsearch().getEnterprise().getAaa().getEnabled()) {
                    curlrc += String.format(Locale.ROOT, "user = %s:%s\n", username, password);
                }
            }

            final V1ObjectMeta secretMetadata = clusterObjectMeta(OperatorNames.clusterRcFilesSecret(dataCenter));
            final V1Secret secret = new V1Secret()
                    .metadata(secretMetadata)
                    .type("Opaque")
                    .putStringDataItem("cqlshrc", cqlshrc)
                    .putStringDataItem("curlrc", curlrc);
            if (dataCenterSpec.getCassandra().getSsl())
                secret.putStringDataItem("nodetool-ssl.properties", nodetoolSsl());
            return secret;
        }

        public Single<V1beta1PodDisruptionBudget> buildPodDisruptionBudget() throws ApiException {
            final V1ObjectMeta podDisruptionBudgetMetadata = dataCenterObjectMeta(dataCenter.getMetadata().getName());
            V1beta1PodDisruptionBudget podDisruptionBudget = new V1beta1PodDisruptionBudget()
                    .metadata(podDisruptionBudgetMetadata)
                    .spec(new V1beta1PodDisruptionBudgetSpec()
                            .maxUnavailable(new IntOrString(dataCenterSpec.getMaxPodUnavailable()))
                            .selector(new V1LabelSelector().matchLabels(OperatorLabels.datacenter(dataCenter)))
                    );
            return k8sResourceUtils.createOrReplaceNamespacedPodDisruptionBudget(podDisruptionBudget);
        }

        public Single<V1StatefulSet> buildStatefulSetRack(RackStatus rackStatus, ConfigMapVolumeMounts configMapVolumeMounts) throws Exception {
            final V1ObjectMeta statefulSetMetadata = rackObjectMeta(rackStatus);

            // create Elassandra container and the associated initContainer to replay commitlogs
            final V1Container cassandraContainer = buildElassandraContainer(rackStatus);
            final V1Container commitlogInitContainer = buildInitContainerCommitlogReplayer(rackStatus);

            if (dataCenterSpec.getPrometheus().getEnabled()) {
                cassandraContainer.addPortsItem(new V1ContainerPort().name(PROMETHEUS_PORT_NAME).containerPort(dataCenterSpec.getPrometheus().getPort()));
            }

            final V1PodSpec podSpec = (dataCenterSpec.getPodTemplate() != null && dataCenterSpec.getPodTemplate().getSpec() != null
                    ? dataCenterSpec.getPodTemplate().getSpec()
                    : new V1PodSpec())
                    .securityContext(new V1PodSecurityContext().fsGroup(CASSANDRA_GROUP_ID))
                    .addInitContainersItem(buildInitContainerVmMaxMapCount())
                    .addContainersItem(cassandraContainer)
                    .addVolumesItem(new V1Volume()
                            .name("podinfo")
                            .downwardAPI(new V1DownwardAPIVolumeSource()
                                    .addItemsItem(new V1DownwardAPIVolumeFile()
                                            .path("labels")
                                            .fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.labels"))
                                    )
                                    .addItemsItem(new V1DownwardAPIVolumeFile()
                                            .path("annotations")
                                            .fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.annotations"))
                                    )
                                    .addItemsItem(new V1DownwardAPIVolumeFile()
                                            .path("namespace")
                                            .fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.namespace"))
                                    )
                                    .addItemsItem(new V1DownwardAPIVolumeFile()
                                            .path("name")
                                            .fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.name"))
                                    )
                            )
                    )
                    .addVolumesItem(new V1Volume()
                            .name("cassandra-log-volume")
                            .emptyDir(new V1EmptyDirVolumeSource())
                    )
                    .addVolumesItem(new V1Volume()
                            .name("nodeinfo")
                            .emptyDir(new V1EmptyDirVolumeSource())
                    )
                    .addVolumesItem(new V1Volume()
                            .name("operator-truststore-volume")
                            .secret(new V1SecretVolumeSource()
                                    .secretName(AuthorityManager.OPERATOR_TRUSTORE_SECRET_NAME)
                            )
                    )
                    .addVolumesItem(new V1Volume()
                            .name("cqlshrc-volume")
                            .secret(new V1SecretVolumeSource()
                                    .secretName(OperatorNames.clusterRcFilesSecret(dataCenter))
                                    .addItemsItem(new V1KeyToPath()
                                            .key("cqlshrc").path("cqlshrc").mode(256)
                                    )
                            )
                    )
                    .addVolumesItem(new V1Volume()
                            .name("curlrc-volume")
                            .secret(new V1SecretVolumeSource()
                                    .secretName(OperatorNames.clusterRcFilesSecret(dataCenter))
                                    .addItemsItem(new V1KeyToPath()
                                            .key("curlrc").path(".curlrc").mode(256)
                                    )
                            )
                    );

            // use the elassandra service account if not specified in podTemplate
            if (podSpec.getServiceAccountName() == null) {
                podSpec.setServiceAccountName(dataCenterSpec.getServiceAccount());
            }

            // https://kubernetes.io/fr/docs/concepts/services-networking/dns-pod-service/#politique-dns-du-pod
            if (dataCenterSpec.getNetworking().getHostNetworkEnabled()) {
                podSpec.setHostNetwork(true);
                podSpec.setDnsPolicy("ClusterFirstWithHostNet");    // allow service DNS resolution
            }

            if (dataCenterSpec.getCassandra().getSsl()) {
                podSpec.addVolumesItem(new V1Volume()
                        .name("nodetool-ssl-volume")
                        .secret(new V1SecretVolumeSource()
                                .secretName(OperatorNames.clusterRcFilesSecret(dataCenter))
                                .addItemsItem(new V1KeyToPath()
                                        .key("nodetool-ssl.properties").path("nodetool-ssl.properties").mode(256)
                                )
                        )
                );
            }

            // Add the nodeinfo init container to bind on the k8s node public IP if available.
            // If externalDns is enabled, this init-container also publish a DNSEndpoint to expose public DNS name of seed nodes.
            if (dataCenterSpec.getNetworking().nodeInfoRequired()) {
                V1ServiceAccount serviceAccount = saSharedIndexInformer.getIndexer().getByKey(
                        (dataCenterMetadata.getNamespace() == null ? "" : dataCenterMetadata.getNamespace() +"/" ) + OperatorNames.nodeInfoServiceAccount(dataCenter));
                if (serviceAccount == null) {
                    // try a blocking k8s read if not available from the cache.
                    serviceAccount = k8sResourceUtils.readNamespacedServiceAccount(dataCenterMetadata.getNamespace(), OperatorNames.nodeInfoServiceAccount(dataCenter));
                }
                if (serviceAccount != null && serviceAccount.getSecrets() != null && !serviceAccount.getSecrets().isEmpty()) {
                    String nodeInfoSecretName = serviceAccount.getSecrets().get(0).getName();
                    podSpec.addInitContainersItem(buildInitContainerNodeInfo(nodeInfoSecretName, rackStatus));
                } else {
                    logger.warn("datacenter={} nodeinfo secret not found for serviceaccount={}", dataCenter, key);
                }
            }

            {
                if (dataCenterSpec.getImagePullSecrets() != null) {
                    for (String secretName : dataCenterSpec.getImagePullSecrets()) {
                        final V1LocalObjectReference pullSecret = new V1LocalObjectReference().name(secretName);
                        podSpec.addImagePullSecretsItem(pullSecret);
                    }
                }
            }

            // add node affinity for rack, strict or slack (depends on cloud providers PVC cross-zone support)
            final V1NodeSelectorTerm nodeSelectorTerm = new V1NodeSelectorTerm()
                    .addMatchExpressionsItem(new V1NodeSelectorRequirement()
                            .key(OperatorLabels.ZONE).operator("In").addValuesItem(rackStatus.getName()));
            switch (dataCenterSpec.getPodsAffinityPolicy()) {
                case STRICT:
                    podSpec.affinity(new V1Affinity()
                            .nodeAffinity(new V1NodeAffinity()
                                    .requiredDuringSchedulingIgnoredDuringExecution(new V1NodeSelector()
                                            .addNodeSelectorTermsItem(nodeSelectorTerm))));
                    break;
                case SLACK:
                    podSpec.affinity(new V1Affinity()
                            .nodeAffinity(new V1NodeAffinity()
                                    .addPreferredDuringSchedulingIgnoredDuringExecutionItem(new V1PreferredSchedulingTerm()
                                            .weight(100).preference(nodeSelectorTerm))));
                    break;
            }

            // add configmap volumes
            for (final ConfigMapVolumeMountBuilder configMapVolumeMountBuilder : configMapVolumeMounts) {
                logger.trace("Adding configMapVolumeMount name={} path={}", configMapVolumeMountBuilder.mountName, configMapVolumeMountBuilder.mountPath);
                cassandraContainer.addVolumeMountsItem(configMapVolumeMountBuilder.buildV1VolumeMount());
                // the Cassandra container entrypoint overlays configmap volumes
                cassandraContainer.addArgsItem(configMapVolumeMountBuilder.mountPath);

                // commitlogInitContainer must have same volumes as the elassandra one
                commitlogInitContainer.addVolumeMountsItem(configMapVolumeMountBuilder.buildV1VolumeMount());
                commitlogInitContainer.addArgsItem(configMapVolumeMountBuilder.mountPath);

                podSpec.addVolumesItem(new V1Volume()
                        .name(configMapVolumeMountBuilder.mountName)
                        .configMap(configMapVolumeMountBuilder.volumeSource)
                );
            }

            if (dataCenterSpec.getUserSecretVolumeSource() != null) {
                V1VolumeMount userSecretVolMount = new V1VolumeMount()
                        .name("user-secret-volume")
                        .mountPath("/tmp/user-secret-config");
                cassandraContainer.addVolumeMountsItem(userSecretVolMount);
                commitlogInitContainer.addVolumeMountsItem(userSecretVolMount);

                podSpec.addVolumesItem(new V1Volume()
                        .name("user-secret-volume")
                        .secret(dataCenterSpec.getUserSecretVolumeSource())
                );
            }

            // mount JMX password to a env variable for elassandra and sidecar
            final V1EnvVar jmxPasswordEnvVar = new V1EnvVar()
                    .name("JMX_PASSWORD")
                    .valueFrom(new V1EnvVarSource().secretKeyRef(new V1SecretKeySelector()
                            .name(OperatorNames.clusterSecret(dataCenter))
                            .key(KEY_JMX_PASSWORD)));
            cassandraContainer.addEnvItem(jmxPasswordEnvVar);
            commitlogInitContainer.addEnvItem(jmxPasswordEnvVar);

            // mount SSL keystores
            if (dataCenterSpec.getCassandra().getSsl()) {
                V1VolumeMount opKeystoreVolMount = new V1VolumeMount().name("datacenter-keystore").mountPath(OPERATOR_KEYSTORE_MOUNT_PATH);
                cassandraContainer.addVolumeMountsItem(opKeystoreVolMount);
                commitlogInitContainer.addVolumeMountsItem(opKeystoreVolMount);

                podSpec.addVolumesItem(new V1Volume().name("datacenter-keystore")
                        .secret(new V1SecretVolumeSource().secretName(OperatorNames.keystoreSecret(dataCenter))
                                .addItemsItem(new V1KeyToPath().key("keystore.p12").path(OPERATOR_KEYSTORE))));

                V1VolumeMount opTruststoreVolMount = new V1VolumeMount().name("datacenter-truststore").mountPath(authorityManager.getPublicCaMountPath());
                cassandraContainer.addVolumeMountsItem(opTruststoreVolMount);
                commitlogInitContainer.addVolumeMountsItem(opTruststoreVolMount);
                podSpec.addVolumesItem(new V1Volume().name("datacenter-truststore")
                        .secret(new V1SecretVolumeSource()
                                .secretName(authorityManager.getPublicCaSecretName(dataCenterSpec.getClusterName()))
                                .addItemsItem(new V1KeyToPath().key(AuthorityManager.SECRET_CACERT_PEM).path(AuthorityManager.SECRET_CACERT_PEM))
                                .addItemsItem(new V1KeyToPath().key(AuthorityManager.SECRET_TRUSTSTORE_P12).path(AuthorityManager.SECRET_TRUSTSTORE_P12))));
            }

            // Cluster secret mounted as config file (e.g AAA shared secret)
            if (dataCenterSpec.getElasticsearch().getEnterprise().getAaa() != null && dataCenterSpec.getElasticsearch().getEnterprise().getAaa().getEnabled()) {
                final String opClusterSecretPath = "/tmp/operator-cluster-secret";
                V1VolumeMount opClusterSecretVolMount = new V1VolumeMount().name("operator-cluster-secret").mountPath(opClusterSecretPath);

                cassandraContainer.addVolumeMountsItem(opClusterSecretVolMount);
                commitlogInitContainer.addVolumeMountsItem(opClusterSecretVolMount);

                podSpec.addVolumesItem(new V1Volume().name("operator-cluster-secret")
                        .secret(new V1SecretVolumeSource().secretName(OperatorNames.clusterSecret(dataCenter))
                                .addItemsItem(new V1KeyToPath().key(KEY_SHARED_SECRET).path("elasticsearch.yml.d/003-shared-secret.yaml"))));

                cassandraContainer.addArgsItem(opClusterSecretPath);
                commitlogInitContainer.addArgsItem(opClusterSecretPath);
            }

            // CRD spec + configMap fingerprint
            String fingerprint = dataCenterSpec.elassandraFingerprint() + "-" + configMapVolumeMounts.fingerPrint();
            final V1ObjectMeta templateMetadata = (dataCenterSpec.getPodTemplate() != null && dataCenterSpec.getPodTemplate().getMetadata() != null
                    ? dataCenterSpec.getPodTemplate().getMetadata()
                    : new V1ObjectMeta())
                    .putAnnotationsItem(OperatorLabels.DATACENTER_FINGERPRINT, fingerprint)
                    .putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, dataCenter.getMetadata().getGeneration().toString())
                    .putAnnotationsItem(OperatorLabels.PVC_DECOMMISSION_POLICY, dataCenterSpec.getDecommissionPolicy().getValue());

            final Map<String, String> rackLabels = OperatorLabels.rack(dataCenter, rackStatus.getName(), rackStatus.getIndex());
            for(Map.Entry<String,String> entry : rackLabels.entrySet())
                templateMetadata.putLabelsItem(entry.getKey(), entry.getValue());

            // add prometheus annotations to scrap nodes
            if (dataCenterSpec.getPrometheus().getEnabled()) {
                templateMetadata.putAnnotationsItem("prometheus.io/scrape", "true");
                templateMetadata.putAnnotationsItem("prometheus.io/port", Integer.toString(dataCenterSpec.getPrometheus().getPort()));
            }

            // add commitlog replayer init container
            if (dataCenterSpec.getCassandra().getCommitlogsInitContainer() != null && dataCenterSpec.getCassandra().getCommitlogsInitContainer()) {
                podSpec.addInitContainersItem(commitlogInitContainer);
            }

            final V1StatefulSetSpec statefulSetSpec = new V1StatefulSetSpec()
                    // if the serviceName references a headless service, kubeDNS to create an A record for
                    // each pod : $(podName).$(serviceName).$(namespace).svc.cluster.local
                    .serviceName(OperatorNames.nodesService(dataCenter))
                    .replicas(rackStatus.getDesiredReplicas())
                    .selector(new V1LabelSelector().matchLabels(rackLabels))
                    .template(new V1PodTemplateSpec()
                            .metadata(templateMetadata)
                            .spec(podSpec)
                    );

            Zone zone = zones.get(rackStatus.getName());
            if (zone != null && zone.getSts().isPresent()) {
                // Avoid PVC replacement and data loss if spec modified...
                statefulSetSpec.setVolumeClaimTemplates(zone.getSts().get().getSpec().getVolumeClaimTemplates());
            } else {
                if (dataCenterSpec.getDataVolumeClaim() != null && dataCenterSpec.getDataVolumeClaim().getStorageClassName() != null) {
                    String storageClassName = dataCenterSpec.getDataVolumeClaim().getStorageClassName()
                            .replace("{zone}", rackStatus.getName())
                            .replace("{index}", Integer.toString(rackStatus.getIndex()));
                    logger.info("sts={}/{} rack={} creating new PVC with storageClassName={}",
                            statefulSetMetadata.getName(), rackStatus.getName(), dataCenterMetadata.getNamespace(), storageClassName);

                    V1PersistentVolumeClaimSpec v1PersistentVolumeClaimSpec = new V1PersistentVolumeClaimSpec()
                            .accessModes(dataCenterSpec.getDataVolumeClaim().getAccessModes())
                            .dataSource(dataCenterSpec.getDataVolumeClaim().getDataSource())
                            .resources(dataCenterSpec.getDataVolumeClaim().getResources())
                            .selector(dataCenterSpec.getDataVolumeClaim().getSelector())
                            .volumeMode(dataCenterSpec.getDataVolumeClaim().getVolumeMode())
                            .storageClassName(storageClassName);
                    statefulSetSpec.setVolumeClaimTemplates(ImmutableList.of(new V1PersistentVolumeClaim()
                            .metadata(new V1ObjectMeta().name("data-volume")).spec(v1PersistentVolumeClaimSpec)));
                }
            }

            return k8sResourceUtils.createOrReplaceNamespacedStatefulSet(new V1StatefulSet().metadata(statefulSetMetadata).spec(statefulSetSpec))
                    .map(s -> {
                        statefulsetCache.updateIfAbsent(s);
                        return s;
                    });
        }

        private V1Container buildElassandraContainer(RackStatus rack) {
            final V1Container cassandraContainer = buildElassandraBaseContainer("elassandra", rack)
                    .readinessProbe(new V1Probe()
                            .exec(new V1ExecAction()
                                    .addCommandItem("/ready-probe.sh")
                                    .addCommandItem(dataCenterSpec.getCassandra().getNativePort().toString())
                                    .addCommandItem(dataCenterSpec.getElasticsearch().getHttpPort().toString())
                            )
                            .initialDelaySeconds(15)
                            .timeoutSeconds(5)
                    );
            if (dataCenterSpec.getElasticsearch().getEnabled() && dataCenterSpec.getElasticsearch().getEnterprise().getJmx()) {
                cassandraContainer.lifecycle(new V1Lifecycle().preStop(new V1Handler().exec(new V1ExecAction()
                        .addCommandItem("curl")
                        .addCommandItem("-k")
                        .addCommandItem("-X")
                        .addCommandItem("POST")
                        .addCommandItem(
                                (dataCenterSpec.getElasticsearch().getEnterprise().getHttps() ? "https" : "http") +
                                        "://localhost:$ES_PORT/enterprise/search/disable; " +
                                        "nodetool -u cassandra -pwf /etc/cassandra/jmxremote.password " +
                                        (dataCenterSpec.getJvm().getJmxmpEnabled() ? " --jmxmp " : "") +
                                        (dataCenterSpec.getCassandra().getSsl() ? " --ssl " : "") + " drain"))));
            }
            return cassandraContainer;
        }

        private V1Container buildInitContainerCommitlogReplayer(RackStatus rack) {
            return buildElassandraBaseContainer("commitlog-replayer", rack)
                    .addEnvItem(new V1EnvVar().name("STOP_AFTER_COMMITLOG_REPLAY").value("true"));
        }

        private V1Container buildElassandraBaseContainer(String containerName, RackStatus rack) {
            final V1Container cassandraContainer = new V1Container()
                    .name(containerName)
                    .image(dataCenterSpec.getElassandraImage())
                    .imagePullPolicy(dataCenterSpec.getImagePullPolicy())
                    .terminationMessagePolicy("FallbackToLogsOnError")
                    .securityContext(new V1SecurityContext()
                            .runAsUser(CASSANDRA_USER_ID)
                            .capabilities(new V1Capabilities().add(ImmutableList.of("IPC_LOCK", "SYS_RESOURCE"))))
                    .resources(dataCenterSpec.getResources())
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("data-volume")
                            .mountPath("/var/lib/cassandra")
                    )
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("nodeinfo")
                            .mountPath("/nodeinfo")
                    )
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("podinfo")
                            .mountPath("/etc/podinfo")
                    )
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("operator-truststore-volume")
                            .mountPath("/tmp/operator-truststore")
                            .subPath("truststore")
                    )
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("cassandra-log-volume")
                            .mountPath("/var/log/cassandra")
                    )
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("cqlshrc-volume")
                            .mountPath("/home/cassandra/.cassandra/cqlshrc")
                            .subPath("cqlshrc")
                    )
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("curlrc-volume")
                            .mountPath("/home/cassandra/.curlrc")
                            .subPath(".curlrc")
                    )
                    .addEnvItem(new V1EnvVar().name("JMX_PORT").value(Integer.toString(dataCenterSpec.getJvm().getJmxPort())))
                    .addEnvItem(new V1EnvVar().name("NODETOOL_JMX_PORT").value(Integer.toString(dataCenterSpec.getJvm().getJmxPort())))
                    .addEnvItem(new V1EnvVar().name("CQLS_OPTS").value(dataCenterSpec.getCassandra().getSsl() ? "--ssl" : ""))
                    .addEnvItem(new V1EnvVar().name("ES_SCHEME").value(dataCenterSpec.getElasticsearch().getEnterprise().getHttps() ? "https" : "http"))
                    .addEnvItem(new V1EnvVar().name("ES_PORT").value(Integer.toString(dataCenterSpec.getElasticsearch().getHttpPort())))
                    .addEnvItem(new V1EnvVar().name("NAMESPACE").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.namespace"))))
                    .addEnvItem(new V1EnvVar().name("POD_NAME").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.name"))))
                    .addEnvItem(new V1EnvVar().name("POD_IP").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("status.podIP"))))
                    .addEnvItem(new V1EnvVar().name("NODE_NAME").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("spec.nodeName"))))
                    .addEnvItem(new V1EnvVar().name("CASSANDRA_RACK").value(rack.getName()))
                    .addEnvItem(new V1EnvVar().name("CASSANDRA_RACK_INDEX").value(Integer.toString(rack.getIndex())))
                    .addEnvItem(new V1EnvVar().name("CASSANDRA_DATACENTER").value(dataCenterSpec.getDatacenterName()))
                    .addEnvItem(new V1EnvVar().name("CASSANDRA_CLUSTER").value(dataCenterSpec.getClusterName()))
                    .addEnvItem(new V1EnvVar().name("CASSANDRA_SERVICE").value(dataCenterMetadata.getName()))
                    .addEnvItem(new V1EnvVar().name("SEEDER_TRUSTSTORE").value("/tmp/operator-truststore"))
                    .addEnvItem(new V1EnvVar().name("SEEDER_STORE_TYPE").valueFrom(new V1EnvVarSource()
                            .secretKeyRef(new V1SecretKeySelector()
                                    .name(AuthorityManager.OPERATOR_TRUSTORE_SECRET_NAME)
                                    .key("storetype"))))
                    .addEnvItem(new V1EnvVar().name("SEEDER_TRUSTSTORE_PASSWORD").valueFrom(new V1EnvVarSource()
                            .secretKeyRef(new V1SecretKeySelector()
                                    .name(AuthorityManager.OPERATOR_TRUSTORE_SECRET_NAME)
                                    .key("storepass"))))
                    .addEnvItem(new V1EnvVar().name("CPU_REQUEST").valueFrom(new V1EnvVarSource()
                            .resourceFieldRef(new V1ResourceFieldSelector().containerName(containerName).resource("requests.cpu"))))
                    .addEnvItem(new V1EnvVar().name("CPU_LIMIT").valueFrom(new V1EnvVarSource()
                            .resourceFieldRef(new V1ResourceFieldSelector().containerName(containerName).resource("limits.cpu"))))
                    .addEnvItem(new V1EnvVar().name("MEM_REQUEST").valueFrom(new V1EnvVarSource()
                            .resourceFieldRef(new V1ResourceFieldSelector().containerName(containerName).resource("requests.memory"))))
                    .addEnvItem(new V1EnvVar().name("MEM_LIMIT").valueFrom(new V1EnvVarSource()
                            .resourceFieldRef(new V1ResourceFieldSelector().containerName(containerName).resource("limits.memory"))))
                    ;

            String nodetoolOpts = " -u cassandra -pwf /etc/cassandra/jmxremote.password ";
            nodetoolOpts += dataCenterSpec.getJvm().getJmxmpEnabled() ? " --jmxmp " : "";
            nodetoolOpts += useJmxOverSSL() ? " --ssl " : "";
            cassandraContainer.addEnvItem(new V1EnvVar().name("NODETOOL_OPTS").value(nodetoolOpts));

            if (dataCenterSpec.getCassandra().getSsl()) {
                cassandraContainer.addVolumeMountsItem(new V1VolumeMount()
                        .name("nodetool-ssl-volume")
                        .mountPath("/home/cassandra/.cassandra/nodetool-ssl.properties")
                        .subPath("nodetool-ssl.properties")
                );
            }

            if (dataCenterSpec.getNetworking().getHostPortEnabled() || dataCenterSpec.getNetworking().getHostNetworkEnabled()) {
                // expose only one storage port on node
                if (dataCenterSpec.getCassandra().getSsl()) {
                    addPortsItem(cassandraContainer, dataCenterSpec.getCassandra().getSslStoragePort(), "internode-ssl", true);
                } else {
                    addPortsItem(cassandraContainer, dataCenterSpec.getCassandra().getStoragePort(), "internode", true);
                }
            } else {
                addPortsItem(cassandraContainer, dataCenterSpec.getCassandra().getStoragePort(), "internode", false);
                addPortsItem(cassandraContainer, dataCenterSpec.getCassandra().getSslStoragePort(), "internode-ssl", false);
            }
            addPortsItem(cassandraContainer, dataCenterSpec.getCassandra().getNativePort(), "cql", dataCenterSpec.getNetworking().getHostPortEnabled());
            addPortsItem(cassandraContainer, dataCenterSpec.getJvm().getJmxPort(), "jmx", false);
            addPortsItem(cassandraContainer, dataCenterSpec.getJvm().getJdbPort(), "jdb", false);

            if (dataCenterSpec.getElasticsearch().getEnabled()) {
                cassandraContainer.addPortsItem(new V1ContainerPort().name(ELASTICSEARCH_PORT_NAME).containerPort(dataCenterSpec.getElasticsearch().getHttpPort()));
                cassandraContainer.addPortsItem(new V1ContainerPort().name("transport").containerPort(dataCenterSpec.getElasticsearch().getTransportPort()));
                cassandraContainer.addEnvItem(new V1EnvVar().name("CASSANDRA_DAEMON").value("org.apache.cassandra.service.ElassandraDaemon"));
            } else {
                cassandraContainer.addEnvItem(new V1EnvVar().name("CASSANDRA_DAEMON").value("org.apache.cassandra.service.CassandraDaemon"));
            }

            return cassandraContainer;
        }

        /**
         * System tunning init container
         * @return
         */
        private V1Container buildInitContainerVmMaxMapCount() {
            return new V1Container()
                    .securityContext(new V1SecurityContext().privileged(true))
                    .name("system-tune")
                    .image("busybox")
                    .imagePullPolicy("IfNotPresent")
                    .terminationMessagePolicy("FallbackToLogsOnError")
                    .command(ImmutableList.of("sysctl", "-w",
                            "vm.max_map_count=1048575",
                            "net.ipv4.tcp_keepalive_time=60",
                            "net.ipv4.tcp_keepalive_probes=3",
                            "net.ipv4.tcp_keepalive_intvl=10"));
        }

        /**
         * Node-info init container to use k8s public IP address and manage DNS publication for seed nodes.
         * @param nodeInfoSecretName
         * @param rackStatus
         * @return
         */
        private V1Container buildInitContainerNodeInfo(String nodeInfoSecretName, RackStatus rackStatus) {
            ExternalDns externalDns = dataCenterSpec.getNetworking().getExternalDns();
            externalDns.validate();

            boolean updateDns = externalDns.getEnabled();
            int rackIndex = dataCenterStatus.getZones().indexOf(rackStatus.getName());
            String seedHostname = "cassandra-" + externalDns.getRoot() + "-" + rackIndex;
            String dnsEndpointName = OperatorNames.dataCenterResource(dataCenterSpec) + "-" + rackIndex;

            String dnsEndpointManifest = "apiVersion: externaldns.k8s.io/v1alpha1 \n" +
                        "kind: DNSEndpoint\n" +
                        "metadata:\n" +
                        "  name: " + dnsEndpointName + "\n" +
                        "  namespace: " + dataCenterMetadata.getNamespace() + "\n" +
                        "  ownerReferences:\n" +
                        "  - apiVersion: " + StrapdataCrdGroup.GROUP+"/" + DataCenter.VERSION + "\n" +
                        "    controller: true\n" +
                        "    blockOwnerDeletion: true\n" +
                        "    kind: ElassandraDatacenter\n" +
                        "    name: " + dataCenterMetadata.getName() + "\n" +
                        "    uid: " + dataCenterMetadata.getUid() + "\n" + // Datacenter UUID is mandatory to allow Cascading deletion
                        "spec:\n" +
                        "  endpoints:\n" +
                        "  - dnsName: " + seedHostname + "-__POD_INDEX__." + externalDns.getDomain() + "\n" +
                        "    recordTTL: " + externalDns.getTtl() + "\n" +
                        "    recordType: A\n" +
                        "    targets:\n" +
                        "    - __NODE_IP__ ";

                if (logger.isTraceEnabled()) {
                    logger.trace("Template generated for DNSEndpoint CRD : {}", dnsEndpointManifest);
                }

                return new V1Container()
                    .securityContext(new V1SecurityContext().privileged(true))
                    .name("nodeinfo")
                    .image("bitnami/kubectl")
                    .imagePullPolicy("IfNotPresent")
                    .terminationMessagePolicy("FallbackToLogsOnError")
                    .command(ImmutableList.of("sh", "-c",
                            " kubectl get no ${NODE_NAME} --token=\"$NODEINFO_TOKEN\" -o go-template='{{index .metadata.labels \"failure-domain.beta.kubernetes.io/zone\"}}' | awk '!/<no value>/ { print $0 }' > /nodeinfo/zone " +
                                    " && kubectl get no ${NODE_NAME} --token=\"$NODEINFO_TOKEN\" -o go-template='{{index .metadata.labels \"beta.kubernetes.io/instance-type\"}}'| awk '!/<no value>/ { print $0 }' > /nodeinfo/instance-type " +
                                    " && kubectl get no ${NODE_NAME} --token=\"$NODEINFO_TOKEN\" -o go-template='{{index .metadata.labels \"storagetier\"}}' | awk '!/<no value>/ { print $0 }' > /nodeinfo/storagetier " +
                                    // try first to extract ExternalIP from node
                                    // if ExternalIP isn't set, try to extract public ip annotation
                                    " && kubectl get no ${NODE_NAME} --token=\"$NODEINFO_TOKEN\" -o jsonpath='{.status.addresses[?(@.type==\"ExternalIP\")].address}' > /nodeinfo/public-ip " +
                                    " && ((PUB_IP=`cat /nodeinfo/public-ip` && test \"$PUB_IP\" = \"\" && kubectl get no ${NODE_NAME} --token=\"$NODEINFO_TOKEN\" -o go-template='{{index .metadata.labels \"elassandra.strapdata.com/public-ip\"}}' | awk '!/<no value>/ { print $0 }' > /nodeinfo/public-ip) || true ) " +
                                    " && kubectl get no ${NODE_NAME} --token=\"$NODEINFO_TOKEN\" -o jsonpath='{.status.addresses[?(@.type==\"InternalIP\")].address}' > /nodeinfo/node-ip " +
                                    /*
                                    (dataCenterSpec.getNetworking().getNodeLoadBalancerEnabled() ?
                                            String.format(Locale.ROOT, " && echo \"${POD_NAME//%s/%s}.%s\" > /nodeinfo/public-name ",
                                                    "elassandra-" + dataCenterSpec.getClusterName() + "-" + dataCenterSpec.getDatacenterName(),
                                                    "cassandra-" + dataCenterSpec.getExternalDns().getRoot(),
                                                    dataCenterSpec.getExternalDns().getDomain()) :
                                            "") +
                                     */
                                    " && grep ^ /nodeinfo/* " +
                                    // here we create the CRD for ExternalDNS in order to register the Seed as DNS A Record (only node 0 of each rack is registered)
                                    (updateDns ?
                                            " && POD_INDEX=$(echo $POD_NAME | awk -F\"-\" '{print $NF}') " +
                                            " && NODE_IP=$(cat /nodeinfo/public-ip) " +
                                            " && echo \"" + dnsEndpointManifest + "\" > /tmp/dns-manifest.yaml " +
                                            " && sed -i \"s#__NODE_IP__#${NODE_IP}#g\" /tmp/dns-manifest.yaml " +
                                            " && sed -i \"s#__POD_INDEX__#${POD_INDEX}#g\" /tmp/dns-manifest.yaml " +
                                            " && cat /tmp/dns-manifest.yaml && (kubectl create --token=\"$NODEINFO_TOKEN\" -f /tmp/dns-manifest.yaml || true)" :
                                            "")
                    ))
                    .addVolumeMountsItem(new V1VolumeMount().name("nodeinfo").mountPath("/nodeinfo"))
                    .addEnvItem(new V1EnvVar().name("NODE_NAME").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("spec.nodeName"))))
                    .addEnvItem(new V1EnvVar().name("NODEINFO_TOKEN").valueFrom(new V1EnvVarSource().secretKeyRef(new V1SecretKeySelector().name(nodeInfoSecretName).key("token"))))
                    .addEnvItem(new V1EnvVar().name("POD_NAME").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.name"))));
        }
    }


    /**
     * This class holds information about a kubernetes zone (all k8s nodes in a zone and deployed sts)
     */
    @Data
    public static class Zone {
        final String name;
        Integer size = 0;   // number of nodes in the zone
        Optional<V1StatefulSet> sts;  // attached statefulset

        public Zone(final String name) {
            this.name = name;
        }

        // this scaleComparator returns zone where to add next replicas first
        private static final Comparator<Zone> scaleComparator = Comparator
                // smallest replicas first
                .comparingInt(Zone::getSize)
                // in case there is equality, pick the zone with the most slots available
                .thenComparingInt(z -> -z.freeNodeCount())
                // finally use lexical order of the zone name
                .thenComparing(Zone::getName);


        public int replicas() {
            return (!sts.isPresent() || sts.get().getSpec().getReplicas() == null) ? 0 : sts.get().getSpec().getReplicas();
        }

        public int currentReplicas() {
            return (!sts.isPresent() || sts.get().getStatus().getCurrentReplicas() == null) ? 0 : sts.get().getStatus().getCurrentReplicas();
        }

        public int readyReplicas() {
            return (!sts.isPresent() || sts.get().getStatus().getReadyReplicas() == null) ? 0 : sts.get().getStatus().getReadyReplicas();
        }

        public int freeNodeCount() {
            return size - replicas();
        }

        public Optional<String> getDataCenterFingerPrint() {
            Optional<String> result = Optional.empty();
            if (sts.isPresent()) {
                V1ObjectMeta metadata = sts.get().getMetadata();
                if (metadata.getAnnotations() != null) {
                    result = Optional.ofNullable(metadata.getAnnotations().get(OperatorLabels.DATACENTER_FINGERPRINT));
                }
            }
            return result;
        }

        public Optional<Long> getDataCenterGeneration() {
            Optional<Long> result = Optional.empty();
            if (sts.isPresent()) {
                V1ObjectMeta metadata = sts.get().getMetadata();
                if (metadata.getAnnotations() != null) {
                    result = Optional.ofNullable(metadata.getAnnotations().get(OperatorLabels.DATACENTER_GENERATION)).map(Long::valueOf);
                }
            }
            return result;
        }

        public boolean isReady() {
            if (!sts.isPresent())
                return true;
            final V1StatefulSetStatus status = this.sts.get().getStatus();
            return status != null &&
                    sts.get().getSpec().getReplicas() == status.getReadyReplicas() &&
                    (Strings.isNullOrEmpty(status.getUpdateRevision()) ||
                            Objects.equals(status.getUpdateRevision(), status.getCurrentRevision()));
        }

        public boolean isUpdating() {
            V1StatefulSetStatus status = sts.get().getStatus();
            return (status.getUpdateRevision() != null &&
                    status.getUpdateRevision() != status.getCurrentRevision() &&
                    Optional.ofNullable(status.getUpdatedReplicas()).orElse(0) < status.getReplicas());
        }

        public boolean isScalingUp() {
            V1StatefulSetStatus status = sts.get().getStatus();
            return status.getReplicas() < sts.get().getSpec().getReplicas();
        }

        public boolean isScalingDown() {
            V1StatefulSetStatus status = sts.get().getStatus();
            return status.getReplicas() > sts.get().getSpec().getReplicas();
        }

        public boolean isParked() {
            V1StatefulSetStatus status = sts.get().getStatus();
            return status.getReplicas() == sts.get().getSpec().getReplicas() &&
                    status.getReplicas() == 0
                    && Optional.ofNullable(status.getCurrentReplicas()).orElse(0) == 0;
        }

        public ElassandraPod lastPod(DataCenter dataCenter, int rackIndex) {
            return new ElassandraPod(dataCenter, rackIndex, (size - 1));
        }

        public ElassandraPod firstPod(DataCenter dataCenter, int rackIndex) {
            return new ElassandraPod(dataCenter, rackIndex, 0);
        }

        public ElassandraPod pod(DataCenter dataCenter, int rackIndex, int ordinal) {
            return new ElassandraPod(dataCenter, rackIndex, ordinal);
        }

        public List<ElassandraPod> pods(DataCenter dataCenter, int rackIndex) {
            List<ElassandraPod> pods = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                pods.add(new ElassandraPod(dataCenter, rackIndex, i));
            }
            return pods;
        }
    }

    public static class Zones implements Iterable<Zone> {
        TreeMap<String, Zone> zoneMap = new TreeMap<>();    // sort racks

        public Zones(DataCenterStatus dataCenterStatus, Collection<V1Node> nodes, TreeMap<String, V1StatefulSet> existingStatefulSetsByZone) {
            for (V1Node node : nodes.stream()
                    .filter(n -> n.getMetadata().getLabels().get(OperatorLabels.ZONE) != null)
                    .sorted(new Comparator<V1Node>() {
                @Override
                public int compare(V1Node o1, V1Node o2) {
                    String zone1 = o1.getMetadata().getLabels().get(OperatorLabels.ZONE);
                    String zone2 = o2.getMetadata().getLabels().get(OperatorLabels.ZONE);
                    return zone1.compareTo(zone2);
                }
            }).collect(Collectors.toList())) {
                String zoneName = node.getMetadata().getLabels().get(OperatorLabels.ZONE);
                if (!dataCenterStatus.getZones().contains(zoneName)) {
                    // Register the zone name to keep an ordered zones list and compute consistent rackIndex.
                    dataCenterStatus.getZones().add(zoneName);
                }
                zoneMap.compute(zoneName, (k, z) -> {
                    if (z == null) {
                        z = new Zone(zoneName);
                    }
                    z.size++;
                    z.setSts(Optional.ofNullable(existingStatefulSetsByZone.get(zoneName)));
                    return z;
                });
            }
        }

        public Zone get(String zoneName) {
            return zoneMap.get(zoneName);
        }

        public int totalNodes() {
            return zoneMap.values().stream().map(z -> z.size).reduce(0, Integer::sum);
        }

        public int totalReplicas() {
            return zoneMap.values().stream().map(Zone::replicas).reduce(0, Integer::sum);
        }

        public int totalCurrentReplicas() {
            return zoneMap.values().stream().map(Zone::currentReplicas).reduce(0, Integer::sum);
        }

        public int totalReadyReplicas() {
            return zoneMap.values().stream().map(Zone::readyReplicas).reduce(0, Integer::sum);
        }

        public Optional<Zone> nextToScalueUp() {
            return (totalNodes() == totalReplicas()) ? Optional.empty() : zoneMap.values().stream()
                    // filter-out full nodes
                    .filter(z -> z.freeNodeCount() > 0)
                    // select the preferred zone based on some priorities
                    .min(Zone.scaleComparator);
        }

        public Optional<Zone> nextToScaleDown() {
            return (totalReplicas() == 0) ? Optional.empty() : zoneMap.values().stream()
                    // filter-out full nodes
                    .filter(z -> z.replicas() > 0 || !z.sts.isPresent())
                    // select the preferred zone based on some priorities
                    .max(Zone.scaleComparator);
        }

        public Optional<Zone> first() {
            return zoneMap.values().stream().min(Zone.scaleComparator);
        }

        public boolean isReady() {
            return zoneMap.values().stream().allMatch(Zone::isReady);
        }

        public boolean hasConsistentConfiguration() {
            return zoneMap.values().stream()
                    .map(z -> z.getDataCenterFingerPrint())
                    .filter(Optional::isPresent).collect(Collectors.toSet()).size() == 1;
        }

        /**
         * Returns an iterator over elements of type {@code T}.
         *
         * @return an Iterator.
         */
        @Override
        public Iterator<Zone> iterator() {
            return zoneMap.values().iterator();
        }
    }

    private static String toYamlString(final Object object) {
        final DumperOptions options = new DumperOptions();
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(object);
    }
}
