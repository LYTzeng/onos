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
package org.onosproject.k8snetworking.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.fabric8.kubernetes.api.model.networking.NetworkPolicy;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.apache.commons.lang.StringUtils;
import org.apache.karaf.shell.commands.Command;
import org.onosproject.cli.AbstractShellCommand;
import org.onosproject.k8snetworking.api.K8sNetworkPolicyService;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;

import static org.onosproject.k8snetworking.api.Constants.CLI_MARGIN_LENGTH;
import static org.onosproject.k8snetworking.api.Constants.CLI_NAMESPACE_LENGTH;
import static org.onosproject.k8snetworking.api.Constants.CLI_NAME_LENGTH;
import static org.onosproject.k8snetworking.api.Constants.CLI_TYPES_LENGTH;
import static org.onosproject.k8snetworking.util.K8sNetworkingUtil.genFormatString;
import static org.onosproject.k8snetworking.util.K8sNetworkingUtil.prettyJson;

/**
 * Lists kubernetes network policies.
 */
@Command(scope = "onos", name = "k8s-network-policies",
        description = "Lists all kubernetes network policies")
public class K8sNetworkPolicyListCommand extends AbstractShellCommand {

    @Override
    protected void execute() {
        K8sNetworkPolicyService service = get(K8sNetworkPolicyService.class);
        List<NetworkPolicy> policies = Lists.newArrayList(service.networkPolicies());
        policies.sort(Comparator.comparing(p -> p.getMetadata().getName()));

        String format = genFormatString(ImmutableList.of(CLI_NAME_LENGTH,
                CLI_NAMESPACE_LENGTH, CLI_TYPES_LENGTH));

        if (outputJson()) {
            print("%s", json(policies));
        } else {
            print(format, "Name", "Namespace", "Types");

            for (NetworkPolicy policy : policies) {

                print(format,
                        StringUtils.substring(policy.getMetadata().getName(),
                                0, CLI_NAME_LENGTH - CLI_MARGIN_LENGTH),
                        StringUtils.substring(policy.getMetadata().getNamespace(),
                                0, CLI_NAMESPACE_LENGTH - CLI_MARGIN_LENGTH),
                        policy.getSpec().getPolicyTypes().isEmpty() ?
                                "" : policy.getSpec().getPolicyTypes());
            }
        }
    }

    private String json(List<NetworkPolicy> policies) {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode result = mapper.createArrayNode();

        try {
            for (NetworkPolicy policy : policies) {
                ObjectNode json = (ObjectNode) new ObjectMapper()
                        .readTree(Serialization.asJson(policy));
                result.add(json);
            }
            return prettyJson(mapper, result.toString());
        } catch (IOException e) {
            log.warn("Failed to parse Network Policy's JSON string.");
            return "";
        }
    }
}
