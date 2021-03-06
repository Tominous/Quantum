package dev.kscott.quantum.location;

import cloud.commandframework.paper.PaperCommandManager;
import dev.kscott.quantum.config.Config;
import dev.kscott.quantum.exceptions.ExceededMaxRetriesException;
import dev.kscott.quantum.rule.rules.async.AsyncQuantumRule;
import dev.kscott.quantum.rule.rules.sync.SyncQuantumRule;
import dev.kscott.quantum.rule.ruleset.QuantumRuleset;
import dev.kscott.quantum.rule.ruleset.RulesetRegistry;
import dev.kscott.quantum.rule.ruleset.search.SearchArea;
import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * LocationProvider, the core of Quantum. Generates spawn points, handles spawn rules, etc
 */
public class LocationProvider {

    /**
     * Random reference
     */
    private final @NonNull Random random;

    /**
     * PaperCommandManager reference
     */
    private final @NonNull PaperCommandManager<CommandSender> commandManager;

    /**
     * QuantumTimer reference
     */
    private final @NonNull QuantumTimer timer;

    /**
     * Config reference
     */
    private final @NonNull Config config;

    private final @NonNull LocationQueue locationQueue;

    /**
     * Constructs the LocationProvider
     *
     * @param config         {@link this#config}
     * @param timer          {@link this#timer}
     * @param commandManager {@link this#commandManager}
     */
    public LocationProvider(
            final @NonNull Config config,
            final @NonNull QuantumTimer timer,
            final @NonNull PaperCommandManager<CommandSender> commandManager,
            final @NonNull RulesetRegistry rulesetRegistry
    ) {
        this.commandManager = commandManager;
        this.random = new Random();
        this.timer = timer;
        this.config = config;

        this.locationQueue = new LocationQueue(this, rulesetRegistry);
    }

    /**
     * Returns a random spawn location using {@code quantumRuleset}.
     *
     * Will first check if the queue for {@code quantumRuleset} was empty. If it was, then
     * a new location will be generated to complete the {@link CompletableFuture} with.
     * Additionally, this will trigger the LocationQueue to generate more locations for
     * the ruleset (using the ruleset's configured queue target).
     *
     * If there was a location in the queue, then the {@link CompletableFuture} will be completed
     * using the queue.
     *
     * @param quantumRuleset The ruleset to use for this search
     * @return A CompletableFuture<Location>. Will complete when a valid location is found.
     */
    public @NonNull CompletableFuture<QuantumLocation> getSpawnLocation(final @NonNull QuantumRuleset quantumRuleset) {
        final @NonNull CompletableFuture<QuantumLocation> cf = new CompletableFuture<>();

        if (this.locationQueue.getLocationCount(quantumRuleset) == 0) {
            findLocation(0, System.currentTimeMillis(), quantumRuleset, cf);
            this.locationQueue.getLocations(quantumRuleset);
        } else {
            cf.complete(this.locationQueue.popLocation(quantumRuleset));
        }

        this.locationQueue.getLocations(quantumRuleset);

        return cf;
    }

    /**
     * Accesses the Location queue to find a location. If no location was in the queue, it will return null.
     *
     * @param quantumRuleset The target ruleset.
     * @return Location from the queue. May be null if the queue was empty.
     */
    public @Nullable QuantumLocation getQueueLocation(final @NonNull QuantumRuleset quantumRuleset) {
        return this.locationQueue.popLocation(quantumRuleset);
    }

    /**
     * Searches for a location with the given ruleset until it reaches max retries (or finds a valid location)
     *
     * @param tries          how many times has the search been tried (call this with 0)
     * @param start          when was this search started
     * @param quantumRuleset the ruleset to search with
     * @param cf             the CompletableFuture to call when this search completes or fails
     */
    protected void findLocation(final int tries, final long start, final @NonNull QuantumRuleset quantumRuleset, final @NonNull CompletableFuture<QuantumLocation> cf) {
        if (this.config.getMaxRetries() <= tries) {
            cf.completeExceptionally(new ExceededMaxRetriesException());
            return;
        }

        this.commandManager.taskRecipe()
                .begin(quantumRuleset)
                .synchronous(ruleset -> {
                    // Get the world and construct the QuantumState
                    final @Nullable World world = Bukkit.getWorld(ruleset.getWorldUuid());

                    if (world == null) {
                        throw new RuntimeException("World cannot be null!");
                    }

                    final @NonNull QuantumLocationState state = new QuantumLocationState();

                    state.setWorld(world);
                    state.setQuantumRuleset(quantumRuleset);

                    return state;
                })
                .asynchronous(state -> {
                    // Do some random generation
                    final @NonNull SearchArea searchArea = state.getQuantumRuleset().getSearchArea();

                    final int x = random.nextInt((searchArea.getMaxX() - searchArea.getMinX()) + 1) + searchArea.getMinX();
                    final int z = random.nextInt((searchArea.getMaxZ() - searchArea.getMinZ()) + 1) + searchArea.getMinZ();

                    state.setX(x);
                    state.setZ(z);

                    return state;
                })
                .synchronous(state -> {
                    // Get the chunk future and add it to the state
                    state.setChunkFuture(PaperLib.getChunkAtAsync(state.getWorld(), state.getChunkX(), state.getChunkZ()));

                    return state;
                })
                .asynchronous(state -> {
                    // Get the chunk from the future
                    try {
                        state.setChunk(state.getChunkFuture().get());
                    } catch (InterruptedException | ExecutionException e) {
                        throw new RuntimeException("Error getting cf");
                    }

                    return state;
                })
                .synchronous(state -> {
                    // get & set the snapshot
                    state.setSnapshot(state.getChunk().getChunkSnapshot(true, true, false));

                    return state;
                })
                .asynchronous(state -> {
                    // Get the y value and check async rules

                    state.setValid(true);

                    final int y = state.getQuantumRuleset().getYLocator().getValidY(state.getSnapshot(), state.getRelativeX(), state.getRelativeZ());


                    if (y == -1) {
                        state.setValid(false);
                        return state;
                    }

                    state.setY(y);

                    for (final AsyncQuantumRule rule : state.getQuantumRuleset().getAsyncRules()) {
                        boolean valid = rule.validate(state.getSnapshot(), state.getRelativeX(), y, state.getRelativeZ());

                        state.setValid(valid);

                        if (!valid) {
                            break;
                        }
                    }

                    return state;
                })
                .synchronous(state -> {
                    // Check sync rules
                    for (final SyncQuantumRule rule : state.getQuantumRuleset().getSyncRules()) {
                        boolean valid = rule.validate(state.getChunk(), state.getRelativeX(), state.getY(), state.getRelativeZ());

                        state.setValid(valid);

                        if (!valid) {
                            break;
                        }
                    }

                    return state;
                })
                .asynchronous(state -> {
                    // Complete the cf, either with the new location, or the results of a recursive getSpawnLocation call
                    if (state.isValid()) {
                        final long searchTime = System.currentTimeMillis() - start;

                        cf.complete(new QuantumLocation(
                                searchTime,
                                new Location(state.getWorld(), state.getX(), state.getY(), state.getZ()),
                                state.getQuantumRuleset()
                        ));

                        this.timer.addTime(searchTime);
                    } else {
                        findLocation(tries + 1, start, quantumRuleset, cf);
                    }
                })
                .execute();
    }
}
