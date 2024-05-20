/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.gateway;

import org.apache.logging.log4j.Logger;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.routing.RoutingNodes;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.UnassignedInfo;
import org.opensearch.cluster.routing.allocation.AllocateUnassignedDecision;
import org.opensearch.cluster.routing.allocation.NodeAllocationResult;
import org.opensearch.cluster.routing.allocation.RoutingAllocation;
import org.opensearch.cluster.routing.allocation.decider.Decision;
import org.opensearch.common.collect.Tuple;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.gateway.AsyncShardFetch.FetchResult;
import org.opensearch.indices.store.TransportNodesListShardStoreMetadata;
import org.opensearch.indices.store.TransportNodesListShardStoreMetadataBatch.NodeStoreFilesMetadata;
import org.opensearch.indices.store.TransportNodesListShardStoreMetadataBatch.NodeStoreFilesMetadataBatch;
import org.opensearch.indices.store.TransportNodesListShardStoreMetadataHelper.StoreFilesMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Allocates replica shards in a batch mode
 *
 * @opensearch.internal
 */
public abstract class ReplicaShardBatchAllocator extends ReplicaShardAllocator {

    /**
     * Process existing recoveries of replicas and see if we need to cancel them if we find a better
     * match. Today, a better match is one that can perform a no-op recovery while the previous recovery
     * has to copy segment files.
     *
     * @param allocation the overall routing allocation
     * @param shardBatches a list of shard batches to check for existing recoveries
     */
    public void processExistingRecoveries(RoutingAllocation allocation, List<List<ShardRouting>> shardBatches) {
        List<Runnable> shardCancellationActions = new ArrayList<>();
        // iterate through the batches, each batch needs to be processed together as fetch call should be made for shards from same batch
        for (List<ShardRouting> shardBatch : shardBatches) {
            List<ShardRouting> eligibleShards = new ArrayList<>();
            List<ShardRouting> ineligibleShards = new ArrayList<>();
            // iterate over shards to check for match for each of those
            for (ShardRouting shard : shardBatch) {
                if (shard != null && !shard.primary()) {
                    // need to iterate over all the nodes to find matching shard
                    if (shouldSkipFetchForRecovery(shard)) {
                        // shard should just be skipped for fetchData, no need to remove from batch
                        continue;
                    }
                    eligibleShards.add(shard);
                }
            }
            AsyncShardFetch.FetchResult<NodeStoreFilesMetadataBatch> shardState = fetchData(eligibleShards, ineligibleShards, allocation);
            if (!shardState.hasData()) {
                logger.trace("{}: fetching new stores for initializing shard batch", eligibleShards);
                continue; // still fetching
            }
            for (ShardRouting shard : eligibleShards) {
                Map<DiscoveryNode, StoreFilesMetadata> nodeShardStores = convertToNodeStoreFilesMetadataMap(shard, shardState);

                Runnable cancellationAction = cancelExistingRecoveryForBetterMatch(shard, allocation, nodeShardStores);
                if (cancellationAction != null) {
                    shardCancellationActions.add(cancellationAction);
                }
            }
        }
        for (Runnable action : shardCancellationActions) {
            action.run();
        }
    }

    abstract protected FetchResult<NodeStoreFilesMetadataBatch> fetchData(
        List<ShardRouting> eligibleShards,
        List<ShardRouting> ineligibleShards,
        RoutingAllocation allocation
    );

    @Override
    protected FetchResult<TransportNodesListShardStoreMetadata.NodeStoreFilesMetadata> fetchData(
        ShardRouting shard,
        RoutingAllocation allocation
    ) {
        logger.error("fetchData for single shard called via batch allocator");
        throw new IllegalStateException("ReplicaShardBatchAllocator should only be used for a batch of shards");
    }

    @Override
    public AllocateUnassignedDecision makeAllocationDecision(ShardRouting unassignedShard, RoutingAllocation allocation, Logger logger) {
        if (isResponsibleFor(unassignedShard) == false) {
            return AllocateUnassignedDecision.NOT_TAKEN;
        }
        Tuple<Decision, Map<String, NodeAllocationResult>> result = canBeAllocatedToAtLeastOneNode(unassignedShard, allocation);
        Decision allocateDecision = result.v1();
        if (allocateDecision.type() != Decision.Type.YES
            && (allocation.debugDecision() == false || hasInitiatedFetching(unassignedShard) == false)) {
            // only return early if we are not in explain mode, or we are in explain mode but we have not
            // yet attempted to fetch any shard data
            logger.trace("{}: ignoring allocation, can't be allocated on any node", unassignedShard);
            return AllocateUnassignedDecision.no(
                UnassignedInfo.AllocationStatus.fromDecision(allocateDecision.type()),
                result.v2() != null ? new ArrayList<>(result.v2().values()) : null
            );
        }

        final FetchResult<NodeStoreFilesMetadataBatch> shardsState = fetchData(
            List.of(unassignedShard),
            Collections.emptyList(),
            allocation
        );
        Map<DiscoveryNode, StoreFilesMetadata> nodeShardStores = convertToNodeStoreFilesMetadataMap(unassignedShard, shardsState);
        return getAllocationDecision(unassignedShard, allocation, nodeShardStores, result, logger);
    }

    /**
     * Allocate Batch of unassigned shard  to nodes where valid copies of the shard already exists
     * @param shardRoutings the shards to allocate
     * @param allocation the allocation state container object
     */
    public void allocateUnassignedBatch(List<ShardRouting> shardRoutings, RoutingAllocation allocation) {
        HashMap<ShardId, AllocateUnassignedDecision> ineligibleShardAllocationDecisions = new HashMap<>();
        final boolean explain = allocation.debugDecision();
        List<ShardRouting> eligibleShards = new ArrayList<>();
        List<ShardRouting> ineligibleShards = new ArrayList<>();
        HashMap<ShardRouting, Tuple<Decision, Map<String, NodeAllocationResult>>> nodeAllocationDecisions = new HashMap<>();
        for (ShardRouting shard : shardRoutings) {
            if (!isResponsibleFor(shard)) {
                // this allocator n is not responsible for allocating this shard
                ineligibleShards.add(shard);
                ineligibleShardAllocationDecisions.put(shard.shardId(), AllocateUnassignedDecision.NOT_TAKEN);
                continue;
            }

            Tuple<Decision, Map<String, NodeAllocationResult>> result = canBeAllocatedToAtLeastOneNode(shard, allocation);
            Decision allocationDecision = result.v1();
            if (allocationDecision.type() != Decision.Type.YES && (!explain || !hasInitiatedFetching(shard))) {
                // only return early if we are not in explain mode, or we are in explain mode but we have not
                // yet attempted to fetch any shard data
                logger.trace("{}: ignoring allocation, can't be allocated on any node", shard);
                ineligibleShards.add(shard);
                ineligibleShardAllocationDecisions.put(
                    shard.shardId(),
                    AllocateUnassignedDecision.no(
                        UnassignedInfo.AllocationStatus.fromDecision(allocationDecision.type()),
                        result.v2() != null ? new ArrayList<>(result.v2().values()) : null
                    )
                );
                continue;
            }
            // storing the nodeDecisions in nodeAllocationDecisions if the decision is not YES
            // so that we don't have to compute the decisions again
            nodeAllocationDecisions.put(shard, result);

            eligibleShards.add(shard);
        }

        // only fetch data for eligible shards
        final FetchResult<NodeStoreFilesMetadataBatch> shardsState = fetchData(eligibleShards, ineligibleShards, allocation);

        List<ShardId> shardIdsFromBatch = shardRoutings.stream().map(shardRouting -> shardRouting.shardId()).collect(Collectors.toList());
        RoutingNodes.UnassignedShards.UnassignedIterator iterator = allocation.routingNodes().unassigned().iterator();
        while (iterator.hasNext()) {
            ShardRouting unassignedShard = iterator.next();
            // There will be only one entry for the shard in the unassigned shards batch
            // for a shard with multiple unassigned replicas, hence we are comparing the shard ids
            // instead of ShardRouting in-order to evaluate shard assignment for all unassigned replicas of a shard.
            if (!unassignedShard.primary() && shardIdsFromBatch.contains(unassignedShard.shardId())) {
                logger.info("Executing allocation decision for shard {}", shardIdsFromBatch);
                AllocateUnassignedDecision allocateUnassignedDecision;
                if (ineligibleShardAllocationDecisions.containsKey(unassignedShard.shardId())) {
                    allocateUnassignedDecision = ineligibleShardAllocationDecisions.get(unassignedShard.shardId());
                } else {
                    Tuple<Decision, Map<String, NodeAllocationResult>> result = nodeAllocationDecisions.get(unassignedShard);
                    allocateUnassignedDecision = getAllocationDecision(
                        unassignedShard,
                        allocation,
                        convertToNodeStoreFilesMetadataMap(unassignedShard, shardsState),
                        result,
                        logger
                    );
                }
                executeDecision(unassignedShard, allocateUnassignedDecision, allocation, iterator);
            }
        }
    }

    private Map<DiscoveryNode, StoreFilesMetadata> convertToNodeStoreFilesMetadataMap(
        ShardRouting unassignedShard,
        FetchResult<NodeStoreFilesMetadataBatch> data
    ) {
        if (!data.hasData()) {
            return null;
        }

        Map<DiscoveryNode, StoreFilesMetadata> map = new HashMap<>();

        data.getData().forEach((discoveryNode, value) -> {
            Map<ShardId, NodeStoreFilesMetadata> batch = value.getNodeStoreFilesMetadataBatch();
            NodeStoreFilesMetadata metadata = batch.get(unassignedShard.shardId());
            if (metadata != null) {
                map.put(discoveryNode, metadata.storeFilesMetadata());
            }
        });

        return map;
    }
}
