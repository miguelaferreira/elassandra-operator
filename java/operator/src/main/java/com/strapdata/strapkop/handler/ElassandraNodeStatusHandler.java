package com.strapdata.strapkop.handler;

import com.google.common.collect.Sets;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.event.NodeStatusEvent;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateReconcilier;
import io.kubernetes.client.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

@Handler
public class ElassandraNodeStatusHandler extends TerminalHandler<NodeStatusEvent> {
    
    private final Logger logger = LoggerFactory.getLogger(ElassandraNodeStatusHandler.class);
    
    private static final Set<ElassandraNodeStatus> reconcileOperationModes = Sets.immutableEnumSet(
            // Reconcile when nodes switch to NORMAL. There may be pending scale operations that were
            // waiting for a healthy cluster.
            ElassandraNodeStatus.NORMAL,
            
            // Reconcile when nodes have finished decommissioning. This will resume the StatefulSet
            // reconciliation.
            ElassandraNodeStatus.DECOMMISSIONED
    );
    
    private final WorkQueues workQueues;
    private final DataCenterUpdateReconcilier dataCenterReconcilier;
    
    public ElassandraNodeStatusHandler(WorkQueues workQueue, DataCenterUpdateReconcilier dataCenterReconcilier) {
        this.workQueues = workQueue;
        this.dataCenterReconcilier = dataCenterReconcilier;
    }
    
    @Override
    public void accept(NodeStatusEvent event) throws ApiException {
        logger.info("ElassandraNodeStatus event pod={} {} -> {}", event.getPod().id(), event.getPreviousMode(), event.getCurrentMode());
        
        if (event.getCurrentMode() != null && reconcileOperationModes.contains(event.getCurrentMode())) {
            final String clusterName = event.getPod().getCluster();
            logger.debug("pod={} new status={} => triggering dc reconciliation", event.getPod().id(), event.getCurrentMode());
            workQueues.submit(
                    new ClusterKey(clusterName, event.getPod().getNamespace()),
                    dataCenterReconcilier.reconcile(new Key(event.getPod().getParent(), event.getPod().getNamespace())));
        }
    }
}
