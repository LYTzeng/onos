/*
 * Copyright 2019-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.k8snode.impl;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddress;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.packet.IpAddress;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.LeadershipService;
import org.onosproject.cluster.NodeId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.k8snode.api.DefaultK8sNode;
import org.onosproject.k8snode.api.K8sApiConfig;
import org.onosproject.k8snode.api.K8sApiConfigAdminService;
import org.onosproject.k8snode.api.K8sApiConfigEvent;
import org.onosproject.k8snode.api.K8sApiConfigListener;
import org.onosproject.k8snode.api.K8sNode;
import org.onosproject.k8snode.api.K8sNodeAdminService;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.onlab.util.Tools.groupedThreads;
import static org.onosproject.k8snode.api.K8sNode.Type.MASTER;
import static org.onosproject.k8snode.api.K8sNode.Type.MINION;
import static org.onosproject.k8snode.api.K8sNode.Type.EXTOVS;
import static org.onosproject.k8snode.api.K8sNodeService.APP_ID;
import static org.onosproject.k8snode.api.K8sNodeState.PRE_ON_BOARD;
import static org.onosproject.k8snode.api.K8sNodeState.COMPLETE;
import static org.onosproject.k8snode.api.K8sNodeState.EXT_OVS_CREATED;
import static org.onosproject.k8snode.util.K8sNodeUtil.k8sClient;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Handles the state of kubernetes API server configuration.
 */
@Component(immediate = true)
public class DefaultK8sApiConfigHandler {

    private final Logger log = getLogger(getClass());

    private static final String INTERNAL_IP = "InternalIP";
    private static final String K8S_ROLE = "node-role.kubernetes.io";
    private static final String EXT_BRIDGE_IP = "external.bridge.ip";
    private static final String EXT_GATEWAY_IP = "external.gateway.ip";
    private static final String EXT_INTF_NAME = "external.interface.name";
    private static final String EXT_OVS_IP = "external.ovs.ip";
    private static final String EXT_OVS_INTF_NAME = "external.ovs.interface.name";
    private static final String MGMT_INTF_IP = "management.interface.ip";
    private static final String ONOS_IP = "controller.ip";

    private static final String EXT_OVS_NS_BRIDGE_IP = "192.168.60.254";
    private static final String EXT_OVS_MGMT_IP = "172.30.0.254";
    private static final String EXT_OVS_OUTBOUND_INTF_NAME = "eth7";

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected LeadershipService leadershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sApiConfigAdminService k8sApiConfigAdminService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sNodeAdminService k8sNodeAdminService;

    private final ExecutorService eventExecutor = newSingleThreadExecutor(
            groupedThreads(this.getClass().getSimpleName(), "event-handler", log));

    private final K8sApiConfigListener k8sApiConfigListener = new InternalK8sApiConfigListener();

    private ApplicationId appId;
    private NodeId localNode;
    
    private String exernalOvsIp;
    private IpAddress onosControllerIp;

    @Activate
    protected void activate() {
        appId = coreService.getAppId(APP_ID);
        localNode = clusterService.getLocalNode().id();
        leadershipService.runForLeadership(appId.name());
        k8sApiConfigAdminService.addListener(k8sApiConfigListener);

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        k8sApiConfigAdminService.removeListener(k8sApiConfigListener);
        leadershipService.withdraw(appId.name());
        eventExecutor.shutdown();

        log.info("Stopped");
    }

    /**
     * Checks the validity of the given kubernetes API server configuration.
     *
     * @param config kubernetes API server configuration
     * @return validity result
     */
    private boolean checkApiServerConfig(K8sApiConfig config) {
        KubernetesClient k8sClient = k8sClient(config);
        return k8sClient != null && k8sClient.getApiVersion() != null;
    }

    private void bootstrapK8sNodes(K8sApiConfig config) {
        KubernetesClient k8sClient = k8sClient(config);

        if (k8sClient == null) {
            log.warn("Failed to connect to kubernetes API server");
            return;
        }

        k8sClient.nodes().list().getItems().forEach(n ->
            k8sNodeAdminService.createNode(buildK8sNode(n))
        );
        // create External OvS node here
        k8sNodeAdminService.createExtOvsNode(buildExternalOvsNode());
    }

    // builder for external OvS node
    private K8sNode buildExternalOvsNode(){
        IpAddress managementIp = IpAddress.valueOf(EXT_OVS_MGMT_IP);
        IpAddress dataIp = IpAddress.valueOf(exernalOvsIp);
        String hostname = "ovs";
        K8sNode.Type nodeType = EXTOVS;
        String extIntf = EXT_OVS_OUTBOUND_INTF_NAME;
        IpAddress extBridgeIp = IpAddress.valueOf(EXT_OVS_NS_BRIDGE_IP);
        
        return DefaultK8sNode.builder()
            .hostname(hostname)
            .managementIp(managementIp)
            .controllerIp(onosControllerIp)
            .dataIp(dataIp)
            .extIntf(extIntf)
            .type(nodeType)
            .state(EXT_OVS_CREATED)
            .extBridgeIp(extBridgeIp)
            .build();
    }

    private K8sNode buildK8sNode(Node node) {
        String hostname = node.getMetadata().getName();
        IpAddress managementIp = null;
        IpAddress dataIp = null;

        for (NodeAddress nodeAddress:node.getStatus().getAddresses()) {
            // FIXME: ExternalIp is not considered currently
            if (nodeAddress.getType().equals(INTERNAL_IP)) {
                dataIp = IpAddress.valueOf(nodeAddress.getAddress());
            }
        }

        String roleStr = node.getMetadata().getLabels().keySet().stream()
                .filter(l -> l.contains(K8S_ROLE))
                .findFirst().orElse(null);

        K8sNode.Type nodeType = MINION;

        if (roleStr != null) {
            String role = roleStr.split("/")[1];
            if (MASTER.name().equalsIgnoreCase(role)) {
                nodeType = MASTER;
            } else {
                nodeType = MINION;
            }
        }

        Map<String, String> annots = node.getMetadata().getAnnotations();

        String extIntf = annots.get(EXT_INTF_NAME);
        String extGatewayIpStr = annots.get(EXT_GATEWAY_IP);
        String extBridgeIpStr = annots.get(EXT_BRIDGE_IP);
        String extOvsIntf = annots.get(EXT_OVS_INTF_NAME);
        String extOvsIpStr = annots.get(EXT_OVS_IP);
        IpAddress controllerIp = IpAddress.valueOf(annots.get(ONOS_IP));

        if(onosControllerIp == null){
            onosControllerIp = controllerIp;
        }

        if (exernalOvsIp == null){
            exernalOvsIp = extOvsIpStr;
        }

        managementIp = IpAddress.valueOf(annots.get(MGMT_INTF_IP));

        return DefaultK8sNode.builder()
                .hostname(hostname)
                .managementIp(managementIp)
                .controllerIp(controllerIp)
                .dataIp(dataIp)
                .extIntf(extIntf)
                .type(nodeType)
                .state(PRE_ON_BOARD)
                .extBridgeIp(IpAddress.valueOf(extBridgeIpStr))
                .extGatewayIp(IpAddress.valueOf(extGatewayIpStr))
                .podCidr(node.getSpec().getPodCIDR())
                .extOvsIntf(extOvsIntf)
                .extOvsIp(IpAddress.valueOf(extOvsIpStr))
                .build();
    }

    /**
     * An internal kubernetes API server config listener.
     * The notification is triggered by K8sApiConfigStore.
     */
    private class InternalK8sApiConfigListener implements K8sApiConfigListener {

        private boolean isRelevantHelper() {
            return Objects.equals(localNode, leadershipService.getLeader(appId.name()));
        }

        @Override
        public void event(K8sApiConfigEvent event) {

            switch (event.type()) {
                case K8S_API_CONFIG_CREATED:
                    eventExecutor.execute(() -> processConfigCreation(event.subject()));
                    break;
                default:
                    break;
            }
        }

        private void processConfigCreation(K8sApiConfig config) {
            if (!isRelevantHelper()) {
                return;
            }

            if (checkApiServerConfig(config)) {
                K8sApiConfig newConfig = config.updateState(K8sApiConfig.State.CONNECTED);
                k8sApiConfigAdminService.updateApiConfig(newConfig);
                bootstrapK8sNodes(config);
            }
        }
    }
}
