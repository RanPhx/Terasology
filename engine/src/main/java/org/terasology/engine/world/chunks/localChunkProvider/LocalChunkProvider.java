// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.world.chunks.localChunkProvider;

import com.google.common.collect.Iterators;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TShortObjectMap;
import gnu.trove.map.hash.TShortObjectHashMap;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.core.GameScheduler;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.entity.EntityStore;
import org.terasology.engine.entitySystem.prefab.Prefab;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.monitoring.chunk.ChunkMonitor;
import org.terasology.engine.persistence.ChunkStore;
import org.terasology.engine.persistence.StorageManager;
import org.terasology.engine.world.BlockEntityRegistry;
import org.terasology.engine.world.block.BeforeDeactivateBlocks;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockManager;
import org.terasology.engine.world.block.BlockRegion;
import org.terasology.engine.world.block.BlockRegionc;
import org.terasology.engine.world.block.OnActivatedBlocks;
import org.terasology.engine.world.block.OnAddedBlocks;
import org.terasology.engine.world.chunks.Chunk;
import org.terasology.engine.world.chunks.ChunkBlockIterator;
import org.terasology.engine.world.chunks.ChunkProvider;
import org.terasology.engine.world.chunks.blockdata.ExtraBlockDataManager;
import org.terasology.engine.world.chunks.event.BeforeChunkUnload;
import org.terasology.engine.world.chunks.event.OnChunkGenerated;
import org.terasology.engine.world.chunks.event.OnChunkLoaded;
import org.terasology.engine.world.chunks.event.PurgeWorldEvent;
import org.terasology.engine.world.chunks.internal.ChunkImpl;
import org.terasology.engine.world.chunks.internal.ChunkRelevanceRegion;
import org.terasology.engine.world.chunks.pipeline.ChunkProcessingPipeline;
import org.terasology.engine.world.chunks.pipeline.stages.ChunkTaskProvider;
import org.terasology.engine.world.generation.impl.EntityBufferImpl;
import org.terasology.engine.world.generator.WorldGenerator;
import org.terasology.engine.world.internal.ChunkViewCore;
import org.terasology.engine.world.internal.ChunkViewCoreImpl;
import org.terasology.engine.world.propagation.light.InternalLightProcessor;
import org.terasology.engine.world.propagation.light.LightMerger;
import org.terasology.gestalt.entitysystem.component.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Provides chunks. Chunks placed in this JVM. Also generated Chunks if needed.
 * <p>
 * Loading/Unload chunks dependent on {@link RelevanceSystem}
 * <p/>
 * Produces events:
 * <p>
 * {@link OnChunkGenerated} when chunk was generated {@link WorldGenerator}
 * <p>
 * {@link OnChunkLoaded} when chunk was loaded from {@link StorageManager}
 * <p>
 * {@link OnActivatedBlocks} when load/generate chunk and chunk have blocks with lifecycle (?) {@see
 * https://github.com/MovingBlocks/Terasology/issues/3244}
 * <p>
 * {@link OnAddedBlocks} when load/generate chunk and chunk have blocks with lifecycle (?) {@see
 * https://github.com/MovingBlocks/Terasology/issues/3244}
 * <p>
 * {@link BeforeChunkUnload} when chunk ready to remove from provider.
 * <p>
 * {@link BeforeDeactivateBlocks} when chunk ready to remove and have block lifecycle.
 */
public class LocalChunkProvider implements ChunkProvider {

    private static final Logger logger = LoggerFactory.getLogger(LocalChunkProvider.class);
    private static final int UNLOAD_PER_FRAME = 64;
    private final EntityManager entityManager;
    private final BlockingQueue<Chunk> readyChunks = Queues.newLinkedBlockingQueue();
    private final Map<Vector3ic, Chunk> chunkCache;

    private final Map<Vector3ic, List<EntityStore>> generateQueuedEntities = new ConcurrentHashMap<>();

    private final StorageManager storageManager;
    private final WorldGenerator generator;
    private final BlockManager blockManager;
    private final ExtraBlockDataManager extraDataManager;
    private ChunkProcessingPipeline loadingPipeline;
    private EntityRef worldEntity = EntityRef.NULL;
    private BlockEntityRegistry registry;

    private RelevanceSystem relevanceSystem;

    private final Sinks.Many<Chunk> unloadPublisher = Sinks.many().unicast().onBackpressureBuffer();
    private final BlockingQueue<TShortObjectMap<TIntList>> deactivateBlocksQueue = Queues.newLinkedBlockingQueue();
    private Optional<Disposable> deactivationDispose = Optional.empty();

    public LocalChunkProvider(StorageManager storageManager, EntityManager entityManager, WorldGenerator generator,
                              BlockManager blockManager, ExtraBlockDataManager extraDataManager,
                              Map<Vector3ic, Chunk> chunkCache) {
        this.storageManager = storageManager;
        this.entityManager = entityManager;
        this.generator = generator;
        this.blockManager = blockManager;
        this.extraDataManager = extraDataManager;
        this.chunkCache = chunkCache;
        ChunkMonitor.fireChunkProviderInitialized(this);
        resetDeactivationPublisher();


    }

    protected Future<Chunk> createOrLoadChunk(Vector3ic chunkPos) {
        Vector3i pos = new Vector3i(chunkPos);
        return loadingPipeline.invokeGeneratorTask(
                pos,
                () -> {
                    ChunkStore chunkStore = storageManager.loadChunkStore(pos);
                    Chunk chunk;
                    EntityBufferImpl buffer = new EntityBufferImpl();
                    if (chunkStore == null) {
                        chunk = new ChunkImpl(pos, blockManager, extraDataManager);
                        generator.createChunk(chunk, buffer);
                        generateQueuedEntities.put(chunk.getPosition(new Vector3i()), buffer.getAll());
                    } else {
                        chunk = chunkStore.getChunk();
                    }
                    return chunk;
                });
    }

    public void setBlockEntityRegistry(BlockEntityRegistry value) {
        this.registry = value;
    }

    @Override
    public ChunkViewCore getSubview(BlockRegionc region, Vector3ic offset) {
        Chunk[] chunks = new Chunk[region.volume()];
        for (Vector3ic chunkPos : region) {
            Chunk chunk = chunkCache.get(chunkPos);
            int index = (chunkPos.x() - region.minX()) + region.getSizeX()
                    * ((chunkPos.z() - region.minZ()) + region.getSizeZ()
                    * (chunkPos.y() - region.minY()));
            chunks[index] = chunk;
        }
        return new ChunkViewCoreImpl(chunks, region, offset, blockManager.getBlock(BlockManager.AIR_ID));
    }

    @Override
    public void setWorldEntity(EntityRef worldEntity) {
        this.worldEntity = worldEntity;
    }


    private void processReadyChunk(final Chunk chunk) {
        Vector3ic chunkPos = chunk.getPosition();
        if (chunkCache.get(chunkPos) != null) {
            return; // TODO move it in pipeline;
        }
        chunkCache.put(new Vector3i(chunkPos), chunk);
        chunk.markReady();
        //TODO, it is not clear if the activate/addedBlocks event logic is correct.
        //See https://github.com/MovingBlocks/Terasology/issues/3244
        ChunkStore store = this.storageManager.loadChunkStore(chunkPos);
        TShortObjectMap<TIntList> mappings = createBatchBlockEventMappings(chunk);
        if (store != null) {
            store.restoreEntities();

            PerformanceMonitor.startActivity("Sending OnAddedBlocks");
            mappings.forEachEntry((id, positions) -> {
                if (positions.size() > 0) {
                    blockManager.getBlock(id).getEntity().send(new OnAddedBlocks(positions, registry));
                }
                return true;
            });
            PerformanceMonitor.endActivity();

            // send on activate
            PerformanceMonitor.startActivity("Sending OnActivateBlocks");

            mappings.forEachEntry((id, positions) -> {
                if (positions.size() > 0) {
                    blockManager.getBlock(id).getEntity().send(new OnActivatedBlocks(positions, registry));
                }
                return true;
            });
            PerformanceMonitor.endActivity();
        } else {
            PerformanceMonitor.startActivity("Generating queued Entities");
            generateQueuedEntities.remove(chunkPos).forEach(this::generateQueuedEntities);
            PerformanceMonitor.endActivity();

            // send on activate
            PerformanceMonitor.startActivity("Sending OnActivateBlocks");

            mappings.forEachEntry((id, positions) -> {
                if (positions.size() > 0) {
                    blockManager.getBlock(id).getEntity().send(new OnActivatedBlocks(positions, registry));
                }
                return true;
            });
            PerformanceMonitor.endActivity();


            worldEntity.send(new OnChunkGenerated(chunkPos));
        }
        worldEntity.send(new OnChunkLoaded(chunkPos));
    }

    private void generateQueuedEntities(EntityStore store) {
        Prefab prefab = store.getPrefab();
        EntityRef entity;
        if (prefab != null) {
            entity = entityManager.create(prefab);
        } else {
            entity = entityManager.create();
        }
        for (Component component : store.iterateComponents()) {
            entity.addComponent(component);
        }
    }

    @Override
    public void update() {
        deactivateBlocks();
        checkForUnload();
        Chunk chunk;
        while ((chunk = readyChunks.poll()) != null) {
            processReadyChunk(chunk);
        }
    }

    private void deactivateBlocks() {
        TShortObjectMap<TIntList> entry;
        while ((entry = deactivateBlocksQueue.poll()) != null) {
            entry.forEachEntry((id, positions) -> {
                if (positions.size() > 0) {
                    blockManager.getBlock(id).getEntity().send(new BeforeDeactivateBlocks(positions, registry));
                }
                return true;
            });
        }
    }

    private void checkForUnload() {
        PerformanceMonitor.startActivity("Unloading irrelevant chunks");
        int unloaded = 0;
        Iterator<Vector3ic> iterator = Iterators.concat(
                Iterators.transform(chunkCache.keySet().iterator(), v -> new Vector3i(v.x(), v.y(), v.z())),
                loadingPipeline.getProcessingPosition().iterator());
        while (iterator.hasNext()) {
            Vector3ic pos = iterator.next();
            boolean keep = relevanceSystem.isChunkInRegions(pos); // TODO: move it to relevance system.
            if (!keep && unloadChunkInternal(pos)) {
                iterator.remove();
                if (++unloaded >= UNLOAD_PER_FRAME) {
                    break;
                }

            }
        }
        if (unloaded > 0) {
            logger.debug("Unload {} chunks", unloaded);
        }
        PerformanceMonitor.endActivity();
    }


    private boolean unloadChunkInternal(Vector3ic pos) {
        if (loadingPipeline.isPositionProcessing(pos)) {
            // Chunk hasn't been finished or changed, so just drop it.
            loadingPipeline.stopProcessingAt(pos);
            return false;
        }
        Chunk chunk = chunkCache.get(pos);
        if (chunk == null) {
            return false;
        }

        worldEntity.send(new BeforeChunkUnload(pos));
        storageManager.deactivateChunk(chunk);
        chunk.dispose();


        unloadPublisher.tryEmitNext(chunk);
        return true;
    }


    private TShortObjectMap<TIntList> createBatchBlockEventMappings(Chunk chunk) {
        TShortObjectMap<TIntList> batchBlockMap = new TShortObjectHashMap<>();
        blockManager.listRegisteredBlocks().stream().filter(Block::isLifecycleEventsRequired).forEach(block ->
                batchBlockMap.put(block.getId(), new TIntArrayList()));

        ChunkBlockIterator i = chunk.getBlockIterator();
        while (i.next()) {
            if (i.getBlock().isLifecycleEventsRequired()) {
                TIntList positionList = batchBlockMap.get(i.getBlock().getId());
                positionList.add(i.getBlockPos().x());
                positionList.add(i.getBlockPos().y());
                positionList.add(i.getBlockPos().z());
            }
        }
        return batchBlockMap;
    }

    @Override
    public Chunk getChunk(int x, int y, int z) {
        return getChunk(new Vector3i(x, y, z));
    }

    @Override
    public Chunk getChunk(Vector3ic pos) {
        Chunk chunk = chunkCache.get(pos);
        if (isChunkReady(chunk)) {
            return chunk;
        }
        return null;
    }

    @Override
    public Collection<Chunk> getAllChunks() {
        return chunkCache.values();
    }


    @Override
    public void restart() {
        loadingPipeline.restart();
        resetDeactivationPublisher();
    }

    @Override
    public void shutdown() {
        loadingPipeline.shutdown();
        deactivationDispose.ifPresent(Disposable::dispose);
    }

    @Override
    public void dispose() {
        shutdown();

        for (Chunk chunk : getAllChunks()) {
            unloadChunkInternal(chunk.getPosition());
            chunk.dispose();
        }
        chunkCache.clear();
        /*
         * The chunk monitor needs to clear chunk references, so it's important
         * that no new chunk get created
         */
        ChunkMonitor.fireChunkProviderDisposed(this);
    }

    @Override
    public boolean reloadChunk(Vector3ic coords) {
        if (!chunkCache.containsKey(coords)) {
            return false;
        }

        if (unloadChunkInternal(coords)) {
            chunkCache.remove(coords);
            createOrLoadChunk(coords);
            return true;
        }

        return false;
    }

    private void resetDeactivationPublisher() {
        deactivationDispose.ifPresent(Disposable::dispose);
        deactivationDispose = Optional.of(unloadPublisher.asFlux()
                .parallel(3)
                .runOn(GameScheduler.parallel())
                .<TShortObjectMap<TIntList>>map(chunk -> {
                    TShortObjectMap<TIntList> batchBlockMap = new TShortObjectHashMap<>();
                    for (Block block : blockManager.listRegisteredBlocks()) {
                        if (block.isLifecycleEventsRequired()) {
                            batchBlockMap.put(block.getId(), new TIntArrayList());
                        }
                    }
                    ChunkBlockIterator i = chunk.getBlockIterator();
                    while (i.next()) {
                        if (i.getBlock().isLifecycleEventsRequired()) {
                            TIntList positionList = batchBlockMap.get(i.getBlock().getId());
                            positionList.add(i.getBlockPos().x());
                            positionList.add(i.getBlockPos().y());
                            positionList.add(i.getBlockPos().z());
                        }
                    }
                    return batchBlockMap;
                }).sequential().subscribe(deactivateBlocksQueue::add));
    }

    @Override
    public void purgeWorld() {
        ChunkMonitor.fireChunkProviderDisposed(this);
        loadingPipeline.shutdown();
        resetDeactivationPublisher();

        getAllChunks().stream().filter(Chunk::isReady).forEach(chunk -> {
            worldEntity.send(new BeforeChunkUnload(chunk.getPosition(new Vector3i())));
            storageManager.deactivateChunk(chunk);
            chunk.dispose();
        });
        chunkCache.clear();
        storageManager.deleteWorld();
        worldEntity.send(new PurgeWorldEvent());

        loadingPipeline = new ChunkProcessingPipeline(this::getChunk, relevanceSystem.createChunkTaskComporator());
        loadingPipeline.addStage(
                        ChunkTaskProvider.create("Chunk generate internal lightning",
                                (Consumer<Chunk>) InternalLightProcessor::generateInternalLighting))
                .addStage(ChunkTaskProvider.create("Chunk deflate", Chunk::deflate))
                .addStage(ChunkTaskProvider.createMulti("Light merging",
                        chunks -> {
                            Chunk[] localChunks = chunks.toArray(new Chunk[0]);
                            return new LightMerger().merge(localChunks);
                        },
                        pos -> StreamSupport.stream(new BlockRegion(pos).expand(1, 1, 1).spliterator(), false)
                                .map(Vector3i::new)
                                .collect(Collectors.toSet())
                ))
                .addStage(ChunkTaskProvider.create("Chunk ready", readyChunks::add));


        ChunkMonitor.fireChunkProviderInitialized(this);

        for (ChunkRelevanceRegion chunkRelevanceRegion : relevanceSystem.getRegions()) {
            for (Vector3ic pos : chunkRelevanceRegion.getCurrentRegion()) {
                createOrLoadChunk(pos);
            }
            chunkRelevanceRegion.setUpToDate();
        }
    }

    @Override
    public boolean isChunkReady(Vector3ic pos) {
        return isChunkReady(chunkCache.get(pos));
    }

    private boolean isChunkReady(Chunk chunk) {
        return chunk != null && chunk.isReady();
    }

    // TODO: move loadingPipeline initialization into constructor.
    public void setRelevanceSystem(RelevanceSystem relevanceSystem) {
        this.relevanceSystem = relevanceSystem;
        loadingPipeline = new ChunkProcessingPipeline(this::getChunk, relevanceSystem.createChunkTaskComporator());
        loadingPipeline.addStage(
                        ChunkTaskProvider.create("Chunk generate internal lightning",
                                (Consumer<Chunk>) InternalLightProcessor::generateInternalLighting))
                .addStage(ChunkTaskProvider.create("Chunk deflate", Chunk::deflate))
                .addStage(ChunkTaskProvider.createMulti("Light merging",
                        chunks -> {
                            Chunk[] localChunks = chunks.toArray(new Chunk[0]);
                            return new LightMerger().merge(localChunks);
                        },
                        pos -> StreamSupport.stream(new BlockRegion(pos).expand(1, 1, 1).spliterator(), false)
                                .map(Vector3i::new)
                                .collect(Collectors.toCollection(Sets::newLinkedHashSet))
                ))
                .addStage(ChunkTaskProvider.create("Chunk ready", readyChunks::add));
    }

}
