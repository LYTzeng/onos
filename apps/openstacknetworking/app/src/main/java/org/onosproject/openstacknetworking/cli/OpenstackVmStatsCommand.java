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
package org.onosproject.openstacknetworking.cli;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.karaf.shell.commands.Argument;
import org.apache.karaf.shell.commands.Command;
import org.apache.karaf.shell.commands.Option;
import org.onosproject.cli.AbstractShellCommand;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.device.PortStatistics;
import org.onosproject.openstacknetworking.api.InstancePort;
import org.onosproject.openstacknetworking.api.InstancePortService;
import org.onosproject.openstacknetworking.api.OpenstackNetworkService;
import org.openstack4j.model.network.Port;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Lists port statistic of VM.
 */
@Command(scope = "onos", name = "openstack-vm-stats",
        description = "Show port stats which are attached to the VM")
public class OpenstackVmStatsCommand extends AbstractShellCommand {


    private static final String NO_PORTS = "There's no port associated to given VM Device ID";
    private static final String NO_INSTANCE_PORTS =
            "There's no instance port associated to given VM Device ID";
    private static final String FORMAT =
            "   ip address=%s, port=%s, pktRx=%s, pktTx=%s, bytesRx=%s, bytesTx=%s, pktRxDrp=%s, pktTxDrp=%s, Dur=%s%s";


    @Option(name = "-d", aliases = "--delta",
            description = "Show delta port statistics,"
                    + "only for the last polling interval",
            required = false, multiValued = false)
    private boolean delta = false;

    @Option(name = "-t", aliases = "--table",
            description = "Show delta port statistics in table format "
                    + "using human readable unit",
            required = false, multiValued = false)
    private boolean table = false;

    @Argument(name = "vmDeviceId", description = "VM Device ID")
    private String vmDeviceId = null;

    @Override
    protected void execute() {

        OpenstackNetworkService osNetService = get(OpenstackNetworkService.class);
        InstancePortService osInstanceService = get(InstancePortService.class);
        DeviceService deviceService = get(DeviceService.class);

        Set<Port> ports = osNetService.ports()
                .stream()
                .filter(port -> port.getDeviceId().equals(vmDeviceId))
                .collect(Collectors.toSet());

        if (ports.isEmpty()) {
            print(NO_PORTS);
            return;
        }

        Set<InstancePort> instancePorts = getInstancePortFromNeutronPortList(ports, osInstanceService);
        if (instancePorts.isEmpty()) {
            print(NO_INSTANCE_PORTS);
            return;
        }
        Set<PortNumber> portNumbers =
                instancePorts.stream().map(InstancePort::portNumber).collect(Collectors.toSet());

        Map<PortNumber, String> ipAddressMap = Maps.newHashMap();

        instancePorts.forEach(instancePort -> {

            ipAddressMap.put(instancePort.portNumber(), instancePort.ipAddress().toString());
        });

        instancePorts.stream().findAny().ifPresent(instancePort -> {
            DeviceId deviceId = instancePort.deviceId();
            if (delta) {
                printPortStatsDelta(vmDeviceId, ipAddressMap, deviceService.getPortDeltaStatistics(deviceId));
                if (table) {
                    printPortStatsDeltaTable(
                            vmDeviceId, ipAddressMap, deviceService.getPortDeltaStatistics(deviceId));
                }
            } else {
                printPortStats(vmDeviceId, ipAddressMap, deviceService.getPortStatistics(deviceId));
            }
        });
    }

    private Set<InstancePort> getInstancePortFromNeutronPortList(Set<Port> ports,
                                                                 InstancePortService service) {
        Set<InstancePort> instancePorts = Sets.newHashSet();

        ports.forEach(port -> instancePorts.add(service.instancePort(port.getId())));

        return instancePorts;
    }

    private void printPortStatsDelta(String vmDeviceId,
                                     Map<PortNumber, String> ipAddressMap,
                                     Iterable<PortStatistics> portStats) {
        final String formatDelta = "   ip address=%s, port=%s, pktRx=%s, pktTx=%s, bytesRx=%s, bytesTx=%s,"
                + " rateRx=%s, rateTx=%s, pktRxDrp=%s, pktTxDrp=%s, interval=%s";

        print("VM Device ID: %s", vmDeviceId);

        for (PortStatistics stat : sortByPort(portStats)) {
            if (ipAddressMap.containsKey(stat.portNumber())) {
                float duration = ((float) stat.durationSec()) +
                        (((float) stat.durationNano()) / TimeUnit.SECONDS.toNanos(1));
                float rateRx = stat.bytesReceived() * 8 / duration;
                float rateTx = stat.bytesSent() * 8 / duration;
                print(formatDelta,
                        ipAddressMap.get(stat.portNumber()),
                        stat.portNumber(),
                        stat.packetsReceived(),
                        stat.packetsSent(),
                        stat.bytesReceived(),
                        stat.bytesSent(),
                        String.format("%.1f", rateRx),
                        String.format("%.1f", rateTx),
                        stat.packetsRxDropped(),
                        stat.packetsTxDropped(),
                        String.format("%.3f", duration));
            }
        }
    }

    private void printPortStatsDeltaTable(String vmDeviceId,
                                          Map<PortNumber, String> ipAddressMap,
                                          Iterable<PortStatistics> portStats) {

        final String formatDeltaTable = "|%15s  |%5s | %7s | %7s |  %7s | %7s | %7s | %7s |  %7s | %7s |%9s |";
        print("+----------------------------------------------------------------------" +
                "-----------------------------------------------+");
        print("| VM Device ID = %-100s |", vmDeviceId);
        print("|----------------------------------------------------------------------" +
                "-----------------------------------------------|");
        print("|                 |      | Receive                                | Transmit    " +
                "                           | Time [s] |");
        print("| ip              | Port | Packets |  Bytes  | Rate bps |   Drop  | Packets |  " +
                "Bytes  | Rate bps |   Drop  | Interval |");
        print("|----------------------------------------------------------------------" +
                "-----------------------------------------------|");

        for (PortStatistics stat : sortByPort(portStats)) {

            if (ipAddressMap.containsKey(stat.portNumber())) {
                float duration = ((float) stat.durationSec()) +
                        (((float) stat.durationNano()) / TimeUnit.SECONDS.toNanos(1));
                float rateRx = duration > 0 ? stat.bytesReceived() * 8 / duration : 0;
                float rateTx = duration > 0 ? stat.bytesSent() * 8 / duration : 0;
                print(formatDeltaTable,
                        ipAddressMap.get(stat.portNumber()),
                        stat.portNumber(),
                        humanReadable(stat.packetsReceived()),
                        humanReadable(stat.bytesReceived()),
                        humanReadableBps(rateRx),
                        humanReadable(stat.packetsRxDropped()),
                        humanReadable(stat.packetsSent()),
                        humanReadable(stat.bytesSent()),
                        humanReadableBps(rateTx),
                        humanReadable(stat.packetsTxDropped()),
                        String.format("%.3f", duration));
            }
        }
        print("|----------------------------------------------------------------------" +
                "-----------------------------------------------|");

    }

    private void printPortStats(String vmDeviceId,
                                Map<PortNumber, String> ipAddressMap,
                                Iterable<PortStatistics> portStats) {
        print("VM Device ID: %s", vmDeviceId);

        for (PortStatistics stat : sortByPort(portStats)) {
            if (ipAddressMap.containsKey(stat.portNumber())) {
                print(FORMAT, ipAddressMap.get(stat.portNumber()),
                        stat.portNumber(), stat.packetsReceived(), stat.packetsSent(), stat.bytesReceived(),
                        stat.bytesSent(), stat.packetsRxDropped(), stat.packetsTxDropped(), stat.durationSec(),
                        annotations(stat.annotations()));
            }
        }
    }

    private static List<PortStatistics> sortByPort(Iterable<PortStatistics> portStats) {
        List<PortStatistics> portStatsList = Lists.newArrayList(portStats);

        portStatsList.sort(Comparator.comparing(ps -> ps.portNumber().toLong()));
        return portStatsList;
    }

    private static String humanReadable(long bytes) {
        int unit = 1000;
        if (bytes < unit) {
            return String.format("%s ", bytes);
        }
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        Character pre = ("KMGTPE").charAt(exp - 1);
        return String.format("%.2f%s", bytes / Math.pow(unit, exp), pre);
    }

    private static String humanReadableBps(float bps) {
        int unit = 1000;
        if (bps < unit) {
            return String.format("%.0f ", bps);
        }
        int exp = (int) (Math.log(bps) / Math.log(unit));
        Character pre = ("KMGTPE").charAt(exp - 1);
        return String.format("%.2f%s", bps / Math.pow(unit, exp), pre);
    }
}
