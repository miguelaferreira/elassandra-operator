package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.RemoteEndpointAwareNettySSLOptions;
import com.datastax.driver.core.SSLOptions;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.AuthenticationException;
import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.policies.DCAwareRoundRobinPolicy;
import com.datastax.driver.core.policies.TokenAwarePolicy;
import com.strapdata.cassandra.k8s.SeedProvider;
import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.Authentication;
import com.strapdata.model.k8s.cassandra.CqlStatus;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cache.CqlConnectionCache;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import javax.inject.Singleton;
import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Create and store cql connections and manager super user roles (cassandra, admin, strapkop)
 */
@Singleton
public class CqlConnectionManager implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(CqlConnectionManager.class);

    private final AuthorityManager authorityManager;
    private final CqlConnectionCache cache;
    private final CoreV1Api coreApi;

    public CqlConnectionManager(AuthorityManager authorityManager, CqlConnectionCache cache, final CoreV1Api coreApi) {
        this.authorityManager = authorityManager;
        this.cache = cache;
        this.coreApi = coreApi;
    }

    /**
     * CQL connection reconciliation : ensure there is always a cql session activated
     */
    public void reconcileConnection(final DataCenter dc) throws StrapkopException, ApiException, SSLException {
        
        // abort if dc is not running
        if (dc.getStatus() == null || !Objects.equals(dc.getStatus().getPhase(), DataCenterPhase.RUNNING)) {
            return ;
        }
        
        final Session session = getConnection(dc);
        if (session == null || session.isClosed()) {
            updateConnection(dc);
        }
    }

    /**
     * Close existing connection to the dc and create a new one that will be stored for later retrieval
     * @param dc the datacenter to connect  to
     * @return a cql session
     * @throws DriverException
     * @throws StrapkopException
     * @throws ApiException
     * @throws SSLException
     */
    public void updateConnection(final DataCenter dc) throws DriverException, StrapkopException, ApiException, SSLException {
        removeConnection(dc);
        logger.info("creating a new CQL connection for {}", dc.getMetadata().getName());

        CqlRole[] roles = dc.getSpec().getAuthentication().equals(Authentication.NONE) ?
                null :
                new CqlRole[] { CqlRole.STRAPKOP_ROLE, CqlRole.DEFAULT_CASSANDRA_ROLE, CqlRole.CASSANDRA_ROLE };

        for(CqlRole role : roles) {
            try {
                connect(dc, role);
                return;
            } catch(AuthenticationException e) {
            } catch(java.lang.IllegalArgumentException e) {
                // thrown when a service has no endpoints, because DNS resolution failed.
                logger.warn("Cannot connect: "+e.getMessage());
            } catch(DriverException exception) {
                dc.getStatus().setCqlStatus(CqlStatus.ERRORED);
                dc.getStatus().setCqlErrorMessage(exception.getMessage());
                throw exception;
            }
        }
        logger.warn("Cannot connect to cluster={} dc={} with roles={}",
                dc.getSpec().getClusterName(), dc.getMetadata().getName(), Arrays.asList(roles));
    }

    Session connect(final DataCenter dc, CqlRole role) throws StrapkopException, ApiException, SSLException {
        if (role.password == null)
            // load the password from k8s secret
            role.loadPassword(CqlRoleManager.getSecret(this.coreApi, dc, role.secretNameProvider.apply(dc)));

        Cluster cluster = createClusterObject(dc, role);
        Session session = cluster.connect();
        logger.debug("Connected with the role={} to cluster={} dc={}", role, dc.getSpec().getClusterName(), dc.getMetadata().getName());
        dc.getStatus().setCqlStatus(CqlStatus.ESTABLISHED);
        dc.getStatus().setCqlErrorMessage("");
        cache.put(new Key(dc.getMetadata()), Tuple.of(cluster, session));
        return session;
    }

    public Session getConnection(final Key dcKey) {
        final Tuple2<Cluster, Session> t = cache.get(dcKey);
        if (t == null) {
            return null;
        }
        return t._2;
    }
    
    public Session getConnection(final DataCenter dc) {
        return getConnection(new Key(dc.getMetadata()));
    }

    public Session getSessionRequireNonNull(final DataCenter dc) throws StrapkopException {
        final Session session = getConnection(dc);
        if (session == null) {
            throw new StrapkopException("no cql connection available to initialize keyspace");
        }
        return session;
    }

    public void removeConnection(final DataCenter dc) {
        final Key key = new Key(dc.getMetadata());
    
        final Tuple2<Cluster, Session> existing = cache.remove(key);
    
        if (existing != null && !existing._1.isClosed()) {
            logger.info("closing existing CQL connection for {}", dc.getMetadata().getName());
            existing._1.close();
        }
    
        dc.getStatus().setCqlStatus(CqlStatus.NOT_STARTED);
    }
    
    private Cluster createClusterObject(final DataCenter dc, final CqlRole credentials) throws StrapkopException, ApiException, SSLException {
        
        // TODO: updateConnection remote seeds as contact point
        final Cluster.Builder builder = Cluster.builder()
                .withClusterName(dc.getSpec().getClusterName())
                .withPort(dc.getSpec().getNativePort())
                .withLoadBalancingPolicy(new TokenAwarePolicy(
                        DCAwareRoundRobinPolicy.builder()
                                .withLocalDc(dc.getSpec().getDatacenterName())
                                .build()));

        // add remote seeds to contact points to be able to adjust RF of system keyspace before starting the first local node.
        if (dc.getSpec().getRemoteSeeds() != null)
            for(String remoteSeed : dc.getSpec().getRemoteSeeds())
                builder.addContactPoint(remoteSeed);

        // add seeds from remote seeders.
        if (dc.getSpec().getRemoteSeeders() != null) {
            for(String remoteSeeder : dc.getSpec().getRemoteSeeders()) {
                try {
                    for(InetAddress addr : SeedProvider.seederCall(remoteSeeder)) {
                        logger.debug("Add remote seed={} from seeder={}", addr.getHostAddress(), remoteSeeder);
                        builder.addContactPoint(addr.getHostAddress());
                    }
                } catch (Exception e) {
                    logger.error("Seeder error", e);
                }
            }
        }

        // contact local nodes is boostraped or first DC in the cluster
        boolean hasSeedBootstrapped = dc.getStatus().getRackStatuses().stream().anyMatch(s -> s.getSeedBootstrapped());
        if (hasSeedBootstrapped ||
                ((dc.getSpec().getRemoteSeeds() == null || dc.getSpec().getRemoteSeeds().isEmpty()) && (dc.getSpec().getRemoteSeeders() == null || dc.getSpec().getRemoteSeeders().isEmpty()))) {
            builder.addContactPoint(OperatorNames.nodesService(dc));
        }


        if (Objects.equals(dc.getSpec().getSsl(), Boolean.TRUE)) {
            builder.withSSL(getSSLOptions());
        }
        
        if (!Objects.equals(dc.getSpec().getAuthentication(), Authentication.NONE)) {
            
            Objects.requireNonNull(credentials);
            
            builder.withCredentials(
                    credentials.getUsername(),
                    credentials.getPassword()
            );
        }

        builder.withoutMetrics(); // disable metric collection

        return builder.build();
    }
    
    private SSLOptions getSSLOptions() throws StrapkopException, ApiException, SSLException {
        
        SslContext sslContext = SslContextBuilder
                .forClient()
                .sslProvider(SslProvider.JDK)
                .trustManager(new ByteArrayInputStream(authorityManager.loadPublicCaFromSecret().getBytes(StandardCharsets.UTF_8)))
                .build();
        
        return new RemoteEndpointAwareNettySSLOptions(sslContext);
    }
    
    @PreDestroy
    @Override
    public void close() {
        logger.info("closing all opened cql connections");
        for (Map.Entry<Key, Tuple2<Cluster, Session>> entry : cache.entrySet()) {
            Cluster cluster = entry.getValue()._1;
            if (!cluster.isClosed()) {
                try {
                    cluster.close();
                }
                catch (DriverException e) {
                    logger.warn("error while closing cql connections", e);
                }
            }
        }
    }
}
