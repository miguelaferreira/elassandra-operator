package com.strapdata.strapkop.ssl;


import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.ssl.utils.CertManager;
import com.strapdata.strapkop.ssl.utils.X509CertificateAndPrivateKey;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Secret;
import io.micronaut.cache.annotation.CacheConfig;
import io.micronaut.caffeine.cache.AsyncLoadingCache;
import io.micronaut.caffeine.cache.Caffeine;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.scheduling.executor.ExecutorFactory;
import io.micronaut.scheduling.executor.UserExecutorConfiguration;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.vavr.Tuple2;
import io.vavr.control.Option;
import org.bouncycastle.operator.OperatorCreationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Read/Write the datacenter root CA in a k8s secret.
 */
@Singleton
@CacheConfig("ca-cache")
public class AuthorityManager {
    private static final Logger logger = LoggerFactory.getLogger(AuthorityManager.class);

    // Operator truststore inherited from the micronaut ssl trust-store
    // see https://docs.micronaut.io/1.3.0/guide/configurationreference.html#io.micronaut.http.ssl.DefaultSslConfiguration$DefaultTrustStoreConfiguration
    public static final String OPERATOR_TRUSTORE_SECRET_NAME = "operator-truststore";
    public static final String OPERATOR_TRUSTORE_MOUNT_PATH = "/tmp/operator-truststore"; // operator truststore mount path

    // datacenter root CA used to generate interla certificates
    public static final String DEFAULT_PUBLIC_CA_SECRET_NAME = "ca-pub"; // public CA certificate, secret available for all pods
    public static final String DEFAULT_PRIVATE_CA_SECRET_NAME = "ca-key"; // secret for issuing certificates, only for some privileged pods

    public static final String DEFAULT_PUBLIC_CA_MOUNT_PATH = "/tmp/datacenter-truststore"; // public CA certificate mount path

    // secret keys
    public static final String SECRET_CA_KEY = "ca.key";
    public static final String SECRET_CACERT_PEM = "cacert.pem";
    public static final String SECRET_TRUSTSTORE_P12 = "truststore.p12";

    private static final String CA_KEYPASS = "changeit";
    private static final String CA_TRUSTPASS = "changeit";

    @Inject
    private CertManager certManager;

    @Inject
    K8sResourceUtils k8sResourceUtils;

    @Inject
    SslConfiguration sslConfiguration;

    private final AsyncLoadingCache<String, X509CertificateAndPrivateKey> cache;

    public AuthorityManager(ExecutorFactory executorFactory,
                            @Named("authority") UserExecutorConfiguration userExecutorConfiguration) {
        this.cache = Caffeine.newBuilder()
                .executor(executorFactory.executorService(userExecutorConfiguration))
                .maximumSize(256)
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .buildAsync(ns -> loadOrGenerateDatatcenterCa(ns).blockingGet());
    }

    public X509CertificateAndPrivateKey get(String namespace) throws ExecutionException, InterruptedException {
        return getAsync(namespace).get();
    }

    public CompletableFuture<X509CertificateAndPrivateKey> getAsync(String namespace) {
        logger.debug("Get CA for namespace={}", namespace);
        return cache.get(namespace);
    }

    public Single<X509CertificateAndPrivateKey> getSingle(String namespace) {
        return Single.fromFuture(getAsync(namespace));
    }

    /**
     * CA secret with public certificate, mounted by all pods
     *
     * @return
     */
    public String getPublicCaSecretName() {
        String caSecretName = System.getenv("PUBLIC_CA_SECRET_NAME");
        return (caSecretName == null) ? AuthorityManager.DEFAULT_PUBLIC_CA_SECRET_NAME : caSecretName;
    }

    /**
     * Public CA files mount path where cacert.pem + truststore.p12 will be visible from pods.
     *
     * @return
     */
    public String getPublicCaMountPath() {
        String caMountPath = System.getenv("PUBLIC_CA_MOUNT_PATH");
        return (caMountPath == null) ? AuthorityManager.DEFAULT_PUBLIC_CA_MOUNT_PATH : caMountPath;
    }

    /**
     * Private CA secret with CA private key, only mounted by the operator
     *
     * @return
     */
    public String getPrivateCaSecretName() {
        String caSecretName = System.getenv("PRIVATE_CA_SECRET_NAME");
        return (caSecretName == null) ? AuthorityManager.DEFAULT_PRIVATE_CA_SECRET_NAME : caSecretName;
    }

    public String getCaKeyPass() {
        String password = System.getenv("CA_KEYPASS");
        return (password == null) ? AuthorityManager.CA_KEYPASS : password;
    }

    public String getCaTrustPass() {
        String password = System.getenv("CA_TRUSTPASS");
        return (password == null) ? AuthorityManager.CA_TRUSTPASS : password;
    }

    /**
     * Store CA in 2 secrets, a public one with the CA Cert, a private one with the CA private key.
     *
     * @param ca
     * @throws ApiException
     * @throws GeneralSecurityException
     * @throws IOException
     * @throws OperatorCreationException
     */
    public Single<X509CertificateAndPrivateKey> storeCaAsSecret(String namespace, X509CertificateAndPrivateKey ca) throws ApiException, GeneralSecurityException, IOException, OperatorCreationException {
        final V1Secret publicSecret = new V1Secret()
                .metadata(new V1ObjectMeta()
                        .name(getPublicCaSecretName())
                        .namespace(namespace)
                        .labels(OperatorLabels.MANAGED))
                .type("Opaque")
                .putStringDataItem(SECRET_CACERT_PEM, ca.getCertificateChainAsString())
                .putDataItem(SECRET_TRUSTSTORE_P12, certManager.generateTruststoreBytes(ca, getCaTrustPass()));
        logger.info("Storing public CA in secret {} in namespace {} secret={}", getPublicCaSecretName(), namespace, publicSecret);
        return k8sResourceUtils.createNamespacedSecret(publicSecret)
                .flatMap(s -> {
                    final V1Secret privateSecret = new V1Secret()
                            .metadata(new V1ObjectMeta()
                                    .name(getPrivateCaSecretName())
                                    .namespace(namespace)
                                    .labels(OperatorLabels.MANAGED))
                            .type("Opaque")
                            .putStringDataItem(SECRET_CA_KEY, ca.getPrivateKeyAsString());
                    logger.info("Storing private CA in secret {} in namespace {}", getPrivateCaSecretName(), namespace);
                    return k8sResourceUtils.createNamespacedSecret(privateSecret).map(s2 -> ca);
                });
    }


    private Single<X509CertificateAndPrivateKey> loadOrGenerateDatatcenterCa(String namespace) {
        return k8sResourceUtils.readOptionalNamespacedSecret(namespace, getPublicCaSecretName())
                .flatMap(caPub -> k8sResourceUtils.readOptionalNamespacedSecret(namespace, getPrivateCaSecretName()).map(caKey -> new Tuple2<>(caPub, caKey)))
                .flatMap(tuple -> {
                    X509CertificateAndPrivateKey ca;
                    final byte[] certsBytes = tuple._1.map(sec -> sec.getData().get(SECRET_CACERT_PEM)).orElse(null);
                    final byte[] key = tuple._2.map(sec -> sec.getData().get(SECRET_CA_KEY)).orElse(null);
                    if (certsBytes == null || key == null) {
                        logger.info("Generating operator root ca for namespace={}", namespace);
                        ca = certManager.generateCa("AutoGeneratedRootCA", getCaKeyPass().toCharArray());
                        return storeCaAsSecret(namespace, ca);
                    } else {
                        return Single.just(new X509CertificateAndPrivateKey(new String(certsBytes), new String(key)));
                    }
                })
                .flatMap(x -> storeOperatorTruststoreAsSecret(namespace).toSingleDefault(x));
    }

    /**
     * Store the elassandra-operator truststore in a namespaced secret.
     * This allow elassandra https connections to the operator.
     * @param namespace
     * @return
     * @throws ApiException
     * @throws IOException
     */
    public Completable storeOperatorTruststoreAsSecret(String namespace) throws ApiException, IOException {
        SslConfiguration.TrustStoreConfiguration trustStoreConfiguration = sslConfiguration.getTrustStore();
        if (trustStoreConfiguration.getPath().isPresent() && trustStoreConfiguration.getType().isPresent() && trustStoreConfiguration.getPassword().isPresent()) {
            byte[] trustStoreBytes = null;
            if (trustStoreConfiguration.getPath().get().startsWith("file:")) {
                trustStoreBytes = Files.readAllBytes(Paths.get(trustStoreConfiguration.getPath().get().substring("file:".length())));
            } else if (trustStoreConfiguration.getPath().get().startsWith("classpath:")) {
                throw new UnsupportedEncodingException("classpath truststore not supported");
            }
            final V1Secret operatorTruststoreSecret = new V1Secret()
                    .metadata(new V1ObjectMeta()
                            .name(OPERATOR_TRUSTORE_SECRET_NAME)
                            .namespace(namespace)
                            .labels(OperatorLabels.MANAGED))
                    .type("Opaque")
                    .putStringDataItem("storetype", trustStoreConfiguration.getType().get())
                    .putStringDataItem("storepass", trustStoreConfiguration.getPassword().get())
                    .putDataItem("truststore", trustStoreBytes);
            return k8sResourceUtils.createOrReplaceNamespacedSecret(operatorTruststoreSecret).ignoreElement();
        }
        return Completable.complete();
    }

    /**
     * Issue a child certificate and private key in a PKC12 keystore protected by the provided password.
     *
     * @param cn
     * @param dnsNames
     * @param ipAddresses
     * @param alias
     * @param password
     * @return
     * @throws Exception
     */
    public byte[] issueCertificateKeystore(X509CertificateAndPrivateKey x509CertificateAndPrivateKey,
                                           String cn,
                                           List<String> dnsNames,
                                           List<InetAddress> ipAddresses,
                                           String alias,
                                           String password) throws GeneralSecurityException, IOException, OperatorCreationException {
        return certManager.generateClientKeystoreBytes(
                x509CertificateAndPrivateKey,
                Option.of(getCaKeyPass()),
                cn,
                dnsNames,
                ipAddresses,
                alias,
                password);
    }

}
