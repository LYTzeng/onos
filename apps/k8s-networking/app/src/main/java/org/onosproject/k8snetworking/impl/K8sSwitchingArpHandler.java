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
package org.onosproject.k8snetworking.impl;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.packet.ARP;
import org.onlab.packet.EthType;
import org.onlab.packet.Ethernet;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.onlab.util.KryoNamespace;
import org.onlab.util.Tools;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.cfg.ConfigProperty;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.LeadershipService;
import org.onosproject.cluster.NodeId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.k8snetworking.api.K8sFlowRuleService;
import org.onosproject.k8snetworking.api.K8sNetwork;
import org.onosproject.k8snetworking.api.K8sNetworkService;
import org.onosproject.k8snetworking.api.K8sPort;
import org.onosproject.k8snetworking.api.K8sServiceService;
import org.onosproject.k8snetworking.util.K8sNetworkingUtil;
import org.onosproject.k8snode.api.K8sNode;
import org.onosproject.k8snode.api.K8sNodeEvent;
import org.onosproject.k8snode.api.K8sNodeListener;
import org.onosproject.k8snode.api.K8sNodeService;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.ConsistentMap;
import org.onosproject.store.service.Serializer;
import org.onosproject.store.service.StorageService;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Dictionary;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.onlab.util.Tools.groupedThreads;
import static org.onosproject.k8snetworking.api.Constants.ARP_BROADCAST_MODE;
import static org.onosproject.k8snetworking.api.Constants.ARP_PROXY_MODE;
import static org.onosproject.k8snetworking.api.Constants.ARP_TABLE;
import static org.onosproject.k8snetworking.api.Constants.DEFAULT_ARP_MODE_STR;
import static org.onosproject.k8snetworking.api.Constants.DEFAULT_GATEWAY_MAC_STR;
import static org.onosproject.k8snetworking.api.Constants.K8S_NETWORKING_APP_ID;
import static org.onosproject.k8snetworking.api.Constants.NODE_IP_PREFIX;
import static org.onosproject.k8snetworking.api.Constants.PRIORITY_ARP_CONTROL_RULE;
import static org.onosproject.k8snetworking.api.Constants.SERVICE_FAKE_MAC_STR;
import static org.onosproject.k8snetworking.api.Constants.SHIFTED_IP_PREFIX;
import static org.onosproject.k8snetworking.api.Constants.EXT_OVS_KBR_INT_MGMT_MAC_STR; // [mod]
import static org.onosproject.k8snetworking.api.Constants.INTG_ARP_TABLE; // [mod]
import static org.onosproject.k8snetworking.util.K8sNetworkingUtil.getPropertyValue;
import static org.onosproject.k8snetworking.util.K8sNetworkingUtil.unshiftIpDomain;

/**
 * Handles ARP packet from containers.
 */
@Component(immediate = true)
public class K8sSwitchingArpHandler {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private static final String GATEWAY_MAC = "gatewayMac";
    private static final String ARP_MODE = "arpMode";

    private static final KryoNamespace SERIALIZER_HOST_MAC = KryoNamespace.newBuilder()
            .register(KryoNamespaces.API)
            .build();

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ComponentConfigService configService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected LeadershipService leadershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected MastershipService mastershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected StorageService storageService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sNodeService k8sNodeService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sNetworkService k8sNetworkService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sFlowRuleService k8sFlowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sServiceService k8sServiceService;

    @Property(name = GATEWAY_MAC, value = DEFAULT_GATEWAY_MAC_STR,
            label = "Fake MAC address for virtual network subnet gateway")
    private String gatewayMac = DEFAULT_GATEWAY_MAC_STR;

    @Property(name = ARP_MODE, value = DEFAULT_ARP_MODE_STR,
            label = "ARP processing mode, broadcast | proxy (default)")
    protected String arpMode = DEFAULT_ARP_MODE_STR;

    private MacAddress gwMacAddress;

    private ConsistentMap<IpAddress, MacAddress> extHostMacStore;

    private final ExecutorService eventExecutor = newSingleThreadExecutor(
            groupedThreads(this.getClass().getSimpleName(), "event-handler", log));

    private final InternalPacketProcessor packetProcessor = new InternalPacketProcessor();
    private final InternalNodeEventListener k8sNodeListener = new InternalNodeEventListener();

    private ApplicationId appId;
    private NodeId localNodeId;

    @Activate
    void activate() {
        appId = coreService.registerApplication(K8S_NETWORKING_APP_ID);
        configService.registerProperties(getClass());
        localNodeId = clusterService.getLocalNode().id();
        leadershipService.runForLeadership(appId.name());
        k8sNodeService.addListener(k8sNodeListener);
        packetService.addProcessor(packetProcessor, PacketProcessor.director(0));

        extHostMacStore = storageService.<IpAddress, MacAddress>consistentMapBuilder()
                .withSerializer(Serializer.using(SERIALIZER_HOST_MAC))
                .withName("k8s-host-mac-store")
                .withApplicationId(appId)
                .build();

        log.info("Started");
    }

    @Deactivate
    void deactivate() {
        packetService.removeProcessor(packetProcessor);
        k8sNodeService.removeListener(k8sNodeListener);
        leadershipService.withdraw(appId.name());
        configService.unregisterProperties(getClass(), false);
        eventExecutor.shutdown();

        log.info("Stopped");
    }

    @Modified
    void modified(ComponentContext context) {
        readComponentConfiguration(context);

        log.info("Modified");
    }

    /**
     * Processes ARP request packets.
     *
     * @param context   packet context
     * @param ethPacket ethernet packet
     */
    private void processPacketIn(PacketContext context, Ethernet ethPacket) {
        // if the ARP mode is configured as broadcast mode, we simply ignore ARP packet_in
        if (ARP_BROADCAST_MODE.equals(getArpMode())) {
            return;
        }

        ARP arpPacket = (ARP) ethPacket.getPayload();
        if (arpPacket.getOpCode() == ARP.OP_REQUEST) {
            processArpRequest(context, ethPacket);
        } else if (arpPacket.getOpCode() == ARP.OP_REPLY) {
            processArpReply(context, ethPacket);
        }
    }

    private void processArpRequest(PacketContext context, Ethernet ethPacket) {
        ARP arpPacket = (ARP) ethPacket.getPayload();
        K8sPort srcPort = k8sNetworkService.ports().stream()
                .filter(p -> p.macAddress().equals(ethPacket.getSourceMAC()))
                .findAny().orElse(null);

        String extOvsInterfaceName = k8sNodeService.completeNodes().stream()
            .map(K8sNode::extOvsPortNum).findFirst().get().name(); // eth1 on k8snode

        if (srcPort == null &&
            !context.inPacket().receivedFrom().port().equals(PortNumber.LOCAL) && 
            k8sNodeService.completeNodes().stream().filter(
                n -> n.k8sMgmtVlanPortNum().equals(context.inPacket().receivedFrom().port()))
            .count() == 0 ) {
            
            log.warn("Failed to find source port(MAC:{})", ethPacket.getSourceMAC());
            return;
        }

        // FIXME: this is a workaround for storing host GW MAC address,
        // need to find a way to store the MAC address in persistent way
        if (context.inPacket().receivedFrom().port().equals(PortNumber.LOCAL)) {
            gwMacAddress = ethPacket.getSourceMAC();
        }

        IpAddress targetIp = Ip4Address.valueOf(arpPacket.getTargetProtocolAddress());
        IpAddress senderIp = Ip4Address.valueOf(arpPacket.getSenderProtocolAddress());

        MacAddress replyMac = k8sNetworkService.ports().stream()
                //        .filter(p -> p.networkId().equals(srcPort.networkId()))
                .filter(p -> p.ipAddress().equals(targetIp))
                .map(K8sPort::macAddress)
                .findAny().orElse(null);

        // long gwIpCnt = k8sNetworkService.networks().stream()
        //         .filter(n -> n.gatewayIp().equals(targetIp))
        //         .count();

        // if (gwIpCnt > 0) {
        //     replyMac = gwMacAddress;
        // }

        // Handeling ARP Req from k8s node to external OvS node if target Ip == dataIp (172.16.x.x)
        K8sNode anyNode = k8sNodeService.nodes().stream().findAny().get();
        String targetIpClassCPrefix = K8sNetworkingUtil.getCclassIpPrefixFromCidr(targetIp.toString());
        String dataIpPrefix = K8sNetworkingUtil.getCclassIpPrefixFromCidr(anyNode.dataIp().toString());
        if (replyMac == null && targetIpClassCPrefix.equals(dataIpPrefix)) {
            long dataIpNodeCount = k8sNodeService.nodes().stream()
                .filter(n -> n.dataIp().equals(targetIp)).count();

            Set<K8sNode> nodes = k8sNodeService.nodes();
            for (K8sNode n : nodes){
                if (dataIpNodeCount > 0 &&
                    context.inPacket().receivedFrom().port().equals(n.k8sMgmtVlanIntf())) {
                        // Set replyMac to the MAC addr of management interface (namely kbr-int-mgmt)
                        replyMac = MacAddress.valueOf(EXT_OVS_KBR_INT_MGMT_MAC_STR);
                        break;
                }
            }
        }

        // Handeling ARP Req from external OvS node if target Ip == dataIp (172.16.x.x)
        if (replyMac == null && targetIpClassCPrefix.equals(dataIpPrefix)) {
            K8sNode targetNode = k8sNodeService.nodes().stream()
                .filter(n -> n.dataIp().equals(targetIp)).findFirst().get();

            replyMac = targetNode.k8sMgmtVlanMac();
        }

        if (replyMac == null) {
            String cidr = k8sNetworkService.networks().stream()
                    .map(K8sNetwork::cidr).findAny().orElse(null);

            if (cidr != null) {
                String unshiftedIp = unshiftIpDomain(targetIp.toString(),
                        SHIFTED_IP_PREFIX, cidr);

                replyMac = k8sNetworkService.ports().stream()
                        .filter(p -> p.ipAddress().equals(IpAddress.valueOf(unshiftedIp)))
                        .map(K8sPort::macAddress)
                        .findAny().orElse(null);
            }
        }

        if (replyMac == null) {
            Set<String> serviceIps = k8sServiceService.services().stream()
                    .map(s -> s.getSpec().getClusterIP())
                    .collect(Collectors.toSet());
            if (serviceIps.contains(targetIp.toString())) {
                replyMac = MacAddress.valueOf(SERVICE_FAKE_MAC_STR);
            }
        }

        if (replyMac == null) {

            if (targetIp.toString().startsWith(NODE_IP_PREFIX)) {
                String targetIpPrefix = targetIp.toString().split("\\.")[1];
                String nodePrefix = NODE_IP_PREFIX + "." + targetIpPrefix;

                String exBridgeCidr = k8sNodeService.nodes(K8sNode.Type.EXTOVS).stream()
                        .map(n -> n.extBridgeIp().toString()).findAny().orElse(null);

                if (exBridgeCidr != null) {
                    String extBridgeIp = unshiftIpDomain(targetIp.toString(),
                            nodePrefix, exBridgeCidr);

                    replyMac = k8sNodeService.nodes(K8sNode.Type.EXTOVS).stream()
                            .filter(n -> extBridgeIp.equals(n.extBridgeIp().toString()))
                            .map(K8sNode::extBridgeMac).findAny().orElse(null);

                    if (replyMac == null) {
                        replyMac = extHostMacStore.asJavaMap().get(
                                IpAddress.valueOf(extBridgeIp));
                    }

                    // if the source hosts are not in k8s cluster range,
                    // we need to manually learn their MAC addresses
                    if (replyMac == null) {
                        ConnectPoint cp = context.inPacket().receivedFrom();
                        K8sNode k8sNode = k8sNodeService.node(cp.deviceId());

                        if (k8sNode != null) {
                            K8sNode extOvs = k8sNodeService.nodes(K8sNode.Type.EXTOVS).stream().findFirst().get();
                            setArpRequest(extOvs.extBridgeMac().toBytes(),
                                    extOvs.extBridgeIp().toOctets(),
                                    IpAddress.valueOf(extBridgeIp).toOctets(),
                                    k8sNode, extOvs);
                            context.block();
                            return;
                        }
                    }
                }
            }
        }

        if (replyMac == null) {
            replyMac = MacAddress.valueOf(gatewayMac);
        }

        if (replyMac == null) {
            log.debug("Failed to find MAC address for {}", targetIp);
            return;
        }

        log.info("Replying ARP: Target IP {}, Target MAC {}", targetIp, replyMac);

        Ethernet ethReply = ARP.buildArpReply(
                targetIp.getIp4Address(),
                replyMac,
                ethPacket);

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(context.inPacket().receivedFrom().port())
                .build();

        packetService.emit(new DefaultOutboundPacket(
                context.inPacket().receivedFrom().deviceId(),
                treatment,
                ByteBuffer.wrap(ethReply.serialize())));

        context.block();
    }

    private void processArpReply(PacketContext context, Ethernet ethPacket) {
        ARP arpPacket = (ARP) ethPacket.getPayload();
        ConnectPoint cp = context.inPacket().receivedFrom();
        K8sNode k8sNode = k8sNodeService.node(cp.deviceId());
        K8sNode extOvs = k8sNodeService.nodes(K8sNode.Type.EXTOVS).stream().findFirst().get();

        if (k8sNode != null &&
                ethPacket.getDestinationMAC().equals(extOvs.extBridgeMac())) {
            IpAddress srcIp = IpAddress.valueOf(IpAddress.Version.INET,
                    arpPacket.getSenderProtocolAddress());
            MacAddress srcMac = MacAddress.valueOf(arpPacket.getSenderHardwareAddress());
            log.info("Received ARP reply: Target IP: {}, MAC: {}", srcIp, srcMac);

            // we only add the host IP - MAC map store once,
            // mutable MAP scenario is not considered for now
            if (!extHostMacStore.containsKey(srcIp)) {
                extHostMacStore.put(srcIp, srcMac);
            }
        }
    }

    private void setArpRequest(byte[] senderMac, byte[] senderIp,
                               byte[] targetIp, K8sNode k8sNode, K8sNode extOvs) {
        Ethernet ethRequest = ARP.buildArpRequest(senderMac,
                                                  senderIp, targetIp, VlanId.NO_VID);

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(extOvs.intgToExtPatchPortNum())
                .build();

        packetService.emit(new DefaultOutboundPacket(
                extOvs.intgBridge(),
                treatment,
                ByteBuffer.wrap(ethRequest.serialize())));
    }

    private String getArpMode() {
        Set<ConfigProperty> properties = configService.getProperties(this.getClass().getName());
        return getPropertyValue(properties, ARP_MODE);
    }

    /**
     * Extracts properties from the component configuration context.
     *
     * @param context the component context
     */
    private void readComponentConfiguration(ComponentContext context) {
        Dictionary<?, ?> properties = context.getProperties();

        String updatedMac = Tools.get(properties, GATEWAY_MAC);
        gatewayMac = updatedMac != null ? updatedMac : DEFAULT_GATEWAY_MAC_STR;
        log.info("Configured. Gateway MAC is {}", gatewayMac);
    }

    /**
     * An internal packet processor which processes ARP request, and results in
     * packet-out ARP reply.
     */
    private class InternalPacketProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }

            Ethernet ethPacket = context.inPacket().parsed();
            if (ethPacket == null || ethPacket.getEtherType() != Ethernet.TYPE_ARP) {
                return;
            }

            eventExecutor.execute(() -> processPacketIn(context, ethPacket));
        }
    }

    /**
     * An internal kubernetes node listener which is used for listening kubernetes
     * node activity. As long as a node is in complete state, we will install
     * default ARP rule to handle ARP request.
     */
    private class InternalNodeEventListener implements K8sNodeListener {

        private boolean isRelevantHelper() {
            return Objects.equals(localNodeId, leadershipService.getLeader(appId.name()));
        }

        @Override
        public void event(K8sNodeEvent event) {
            K8sNode k8sNode = event.subject();
            switch (event.type()) {
                case K8S_NODE_COMPLETE:
                    if(k8sNode.type() == K8sNode.Type.EXTOVS){
                        eventExecutor.execute(() -> processExtOvsNodeCompletion(k8sNode));
                    } else {
                        eventExecutor.execute(() -> processNodeCompletion(k8sNode));
                    }
                    break;
                case K8S_NODE_INCOMPLETE:
                    eventExecutor.execute(() -> processNodeIncompletion(k8sNode));
                    break;
                default:
                    break;
            }
        }

        private void processNodeCompletion(K8sNode node) {
            if (!isRelevantHelper()) {
                return;
            }

            setDefaultArpRule(node, true);
        }

        private void processExtOvsNodeCompletion(K8sNode node) {
            if (!isRelevantHelper()) {
                return;
            }

            setDefaultExtOvsArpRule(node, true);
        }

        private void processNodeIncompletion(K8sNode node) {
            if (!isRelevantHelper()) {
                return;
            }

            setDefaultArpRule(node, false);
        }

        private void setDefaultArpRule(K8sNode node, boolean install) {

            if (getArpMode() == null) {
                return;
            }

            switch (getArpMode()) {
                case ARP_PROXY_MODE:
                    setDefaultArpRuleForProxyMode(node, install);
                    break;
                case ARP_BROADCAST_MODE:
                    // TODO: need to implement broadcast mode
                    log.warn("Not implemented yet.");
                    break;
                default:
                    log.warn("Invalid ARP mode {}. Please use either " +
                            "broadcast or proxy mode.", getArpMode());
                    break;
            }
        }

        private void setDefaultArpRuleForProxyMode(K8sNode node, boolean install) {
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchEthType(EthType.EtherType.ARP.ethType().toShort())
                    .build();

            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .punt()
                    .build();

            k8sFlowRuleService.setRule(
                    appId,
                    node.intgBridge(),
                    selector,
                    treatment,
                    PRIORITY_ARP_CONTROL_RULE,
                    ARP_TABLE,
                    install
            );
        }

        private void setDefaultExtOvsArpRule(K8sNode node, boolean install) {

            if (getArpMode() == null) {
                return;
            }

            switch (getArpMode()) {
                case ARP_PROXY_MODE:
                    setDefaultExtOvsArpRuleForProxyMode(node, install);
                    break;
                case ARP_BROADCAST_MODE:
                    log.warn("Not implemented yet.");
                    break;
                default:
                    log.warn("Invalid ARP mode {}. Please use either " +
                            "broadcast or proxy mode.", getArpMode());
                    break;
            }
        }

        private void setDefaultExtOvsArpRuleForProxyMode(K8sNode node, boolean install) {
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchEthType(EthType.EtherType.ARP.ethType().toShort())
                    .build();

            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .punt()
                    .build();

            k8sFlowRuleService.setRule(
                    appId,
                    node.intgBridge(),
                    selector,
                    treatment,
                    PRIORITY_ARP_CONTROL_RULE,
                    INTG_ARP_TABLE,
                    install
            );
        }
    }
}
