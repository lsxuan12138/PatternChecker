package com.patternchecker.check;

import com.patternchecker.PatternCheckerMod;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.parts.AEBasePart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans every pattern reachable through an ME network (pattern providers and
 * ME storage) and reports problems:
 * <ul>
 *   <li>patterns that cannot be decoded at all (empty or corrupted),</li>
 *   <li>patterns AE2 considers invalid (missing items, removed recipes,
 *       recipe no longer matching the encoded grid),</li>
 *   <li>patterns with no output,</li>
 *   <li>patterns whose encoded operation appears more than once,</li>
 *   <li>patterns whose inputs are neither stocked nor craftable.</li>
 * </ul>
 */
public final class PatternScanner {

    private static final String ADVANCED_AE_PROVIDER_HOST =
            "net.pedroksl.advanced_ae.common.logic.AdvPatternProviderLogicHost";
    private static final String MEK_ENERGISTICS_MACHINE =
            "com.beipuo.mekenergistics.blockentity.api.MeAeMachine";
    private static final String AE2LT_MATRIX_PORT =
            "com.moakiee.ae2lt.blockentity.MatrixPortBlockEntity";
    private static final String AE2LT_PIGMEE_PATTERN_PROVIDER =
            "com.moakiee.ae2lt.blockentity.PigmeePatternProviderBlockEntity";
    private static final String AE2LTPP_STABLE_PATTERN_PROVIDER =
            "com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity";
    private static final String AE2LTPP_ADAPTER_REGISTRY =
            "com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapterRegistry";
    private static final String AE2LTPP_ADAPTER_INTERFACE =
            "com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapter";
    private static final String AE2LTPP_ADAPTER_ITEM =
            "com.moakiee.ae2lt.packaged.item.MultiblockAdapterItem";
    private static final String MMR_CONTROLLER_ACCESSIBLE =
            "es.degrassi.mmreborn.api.controller.ControllerAccessible";
    private static final String MMR_MULTIBLOCK_CONTROLLER =
            "es.degrassi.mmreborn.api.controller.IMultiblockController";
    private static final String MMR_RECIPE_TYPE =
            "modular_machinery_reborn:machine_recipe";

    @FunctionalInterface
    private interface MemberAccessor {
        Object get(Object target) throws ReflectiveOperationException;
    }

    private static final Map<Class<?>, Map<String, java.util.Optional<MemberAccessor>>> MEMBER_ACCESSORS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile java.util.Optional<PackagedAdapterReflection> PACKAGED_ADAPTER_REFLECTION;

    private record PackagedAdapterReflection(Method findAdapter, Method requiredAdapterId,
                                             Method bind, Method stackCovers) {
    }

    /**
     * One scanned pattern with its own list of issues. Used by the tool panel
     * to list each broken pattern exactly once.
     */
    public record ScannedPattern(String itemId, Component name, Component outputDesc, Component inputDesc,
                                 String location, BlockPos pos, int slot,
                                 List<PatternIssue> issues) {
    }

    private record DuplicateInput(List<GenericStack> possibleInputs, long multiplier) {
    }

    private record DuplicateSignature(String patternType, List<DuplicateInput> inputs,
                                      List<GenericStack> outputs) {
    }

    private record DuplicateCandidate(DuplicateSignature signature, ScannedPattern pattern, long copies) {
    }

    private record InputIssueCandidate(PatternIssue issue, List<PatternIssue> ownerIssues,
                                       IPatternDetails.IInput input) {
    }

    private record GridState(KeyCounter stored, ICraftingService crafting,
                             Map<AEKey, Boolean> exactCraftable) {
    }

    private record PreparedRecipe(RecipeType<?> type, List<RecipeRequirement> requirements,
                                  List<RecipeOutput> outputs, String machineId,
                                  boolean requireAllOutputs) {
    }

    private record RecipeMatchKey(DuplicateSignature signature, RecipeType<?> onlyType,
                                  String machineId) {
    }

    private record MachineState(Set<RecipeType<?>> types, boolean hasTarget,
                                boolean craftingOnly, boolean acceptsPlans,
                                boolean crystalGrowthChamber,
                                boolean universalRecipeExecutor,
                                boolean knownNonProcessingTarget,
                                boolean unknownAddonTarget,
                                Set<String> machineIds,
                                Set<String> targetBlockIds) {
    }

    /**
     * Shared state for one scan. Recipe extraction is intentionally cached here:
     * large modpacks can have tens of thousands of recipes, and walking that
     * entire registry once per pattern causes a visible server-tick stall.
     */
    private static final class ScanContext {
        private final Level level;
        private final Map<IGrid, GridState> gridStates = new IdentityHashMap<>();
        private final Map<Object, MachineState> machineStates = new IdentityHashMap<>();
        private final Map<String, List<PreparedRecipe>> machineRecipesByOutput = new HashMap<>();
        private final Map<RecipeMatchKey, Boolean> machineRecipeMatches = new HashMap<>();
        private final Map<Object, Map<DuplicateSignature, ProcessingMachineResult>> packagedProviderMatches =
                new IdentityHashMap<>();
        private final Set<String> loggedMachineMismatches = new HashSet<>();
        private boolean machineRecipeIndexBuilt;

        private ScanContext(Level level) {
            this.level = level;
        }

        private GridState gridState(IGrid grid) {
            return gridStates.computeIfAbsent(grid, ignored -> new GridState(
                    grid.getStorageService().getInventory().getAvailableStacks(),
                    grid.getCraftingService(),
                    new HashMap<>()));
        }

        private List<PreparedRecipe> machineRecipesFor(String identifier) {
            if (!machineRecipeIndexBuilt) {
                machineRecipeIndexBuilt = true;
                var registryAccess = level.registryAccess();
                for (RecipeHolder<?> holder : level.getRecipeManager().getOrderedRecipes()) {
                    Recipe<?> recipe = holder.value();
                    // Some machine mods implement CraftingRecipe for serializer
                    // convenience while registering a distinct machine recipe
                    // type (JDT goo spreading and JDTE infusion are examples).
                    if (recipe.getType() == RecipeType.CRAFTING) {
                        continue;
                    }
                    MmrRecipeData mmrRecipe = mmrRecipeData(recipe);
                    List<RecipeOutput> outputs = List.copyOf(mmrRecipe == null
                            ? recipeOutputs(recipe, registryAccess)
                            : mmrRecipe.outputs());
                    if (outputs.isEmpty()) {
                        continue;
                    }
                    PreparedRecipe prepared = new PreparedRecipe(
                            recipe.getType(),
                            List.copyOf(mmrRecipe == null
                                    ? recipeRequirements(recipe)
                                    : mmrRecipe.requirements()),
                            outputs,
                            mmrRecipe == null ? null : mmrRecipe.machineId(),
                            requiresAllRecipeOutputs(recipe));
                    Set<String> indexedOutputs = new HashSet<>();
                    for (RecipeOutput output : outputs) {
                        if (output.identifier() != null && indexedOutputs.add(output.identifier())) {
                            machineRecipesByOutput
                                    .computeIfAbsent(output.identifier(), ignored -> new ArrayList<>())
                                    .add(prepared);
                        }
                    }
                }
            }
            return machineRecipesByOutput.getOrDefault(identifier, List.of());
        }

        private MachineState machineState(Object host, BlockPos pos) {
            return machineStates.computeIfAbsent(host, ignored -> buildMachineState(host, level, pos));
        }
    }

    public record ScanResult(int totalPatterns, int providerPatterns, int containerPatterns, int storagePatterns,
                             List<Component> verdicts, List<ScannedPattern> patterns, List<PatternIssue> issues) {
        public int errorCount() {
            return (int) issues.stream().filter(i -> i.type() == PatternIssue.Type.ERROR).count();
        }

        public int warningCount() {
            return issues.size() - errorCount();
        }
    }

    private PatternScanner() {
    }

    public static ScanResult scanGrid(IGrid grid, Level level) {
        ScanContext context = new ScanContext(level);
        List<PatternIssue> issues = new ArrayList<>();
        List<Component> verdicts = new ArrayList<>();
        List<ScannedPattern> patterns = new ArrayList<>();
        List<DuplicateCandidate> duplicateCandidates = new ArrayList<>();
        List<InputIssueCandidate> inputIssueCandidates = new ArrayList<>();
        Set<AEKey> scannedCraftingOutputs = new HashSet<>();
        int[] totals = new int[3]; // total, provider, container
        int storagePatterns = 0;

        // Canonical AE2 API: every pattern-holding grid machine implements
        // PatternContainer - the same enumeration AE2's own pattern access
        // terminal uses. This covers any addon automatically.
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PatternContainer container : grid.getMachines(PatternContainer.class)) {
            if (seen.add(container)) {
                scanPatternInventory(container, grid, level, issues, verdicts, patterns,
                        duplicateCandidates, inputIssueCandidates, scannedCraftingOutputs, totals, context);
            }
        }
        // Fallback for machines that expose a pattern inventory without
        // implementing PatternContainer (e.g. AE2LT matrix ports).
        for (IGridNode node : grid.getNodes()) {
            Object owner = node.getOwner();
            if (owner != null && seen.add(owner)) {
                scanPatternInventory(owner, grid, level, issues, verdicts, patterns,
                        duplicateCandidates, inputIssueCandidates, scannedCraftingOutputs, totals, context);
            }
        }

        // Patterns stored in ME storage (e.g. in cells, usable through the pattern access terminal).
        MEStorage storage = grid.getStorageService().getInventory();
        String storageLocation = Component.translatable("patternchecker.location.storage").getString();
        for (var entry : storage.getAvailableStacks()) {
            AEKey key = entry.getKey();
            if (key instanceof AEItemKey itemKey && PatternDetailsHelper.isEncodedPattern(itemKey.toStack())) {
                long copies = Math.max(1L, entry.getLongValue());
                totals[0] = saturatedAdd(totals[0], copies);
                storagePatterns = saturatedAdd(storagePatterns, copies);
                check(itemKey.toStack(), null, grid, level, storageLocation, null, -1, null,
                        issues, verdicts, patterns, duplicateCandidates,
                        inputIssueCandidates, scannedCraftingOutputs, copies, context);
            }
        }

        removeCraftableInputIssues(inputIssueCandidates, scannedCraftingOutputs, issues, level);
        markDuplicatePatterns(duplicateCandidates, issues);
        return new ScanResult(totals[0], totals[1], totals[2], storagePatterns, verdicts, patterns, issues);
    }

    /**
     * Scans a single pattern container that is not attached to any discovered
     * grid (e.g. AE2LT wireless pattern providers). If it wirelessly connects
     * to a network, that network is used as the check context.
     */
    public static ScanResult scanLooseContainer(BlockEntity be, Level level) {
        ScanContext scanContext = new ScanContext(level);
        List<PatternIssue> issues = new ArrayList<>();
        List<Component> verdicts = new ArrayList<>();
        List<ScannedPattern> patterns = new ArrayList<>();
        List<DuplicateCandidate> duplicateCandidates = new ArrayList<>();
        List<InputIssueCandidate> inputIssueCandidates = new ArrayList<>();
        Set<AEKey> scannedCraftingOutputs = new HashSet<>();
        int[] totals = new int[3];
        IGrid context = null;
        if (level instanceof ServerLevel serverLevel) {
            context = WirelessHelper.resolveGrid(serverLevel, be);
        }
        scanPatternInventory(be, context, level, issues, verdicts, patterns,
                duplicateCandidates, inputIssueCandidates, scannedCraftingOutputs, totals, scanContext);
        removeCraftableInputIssues(inputIssueCandidates, scannedCraftingOutputs, issues, level);
        markDuplicatePatterns(duplicateCandidates, issues);
        return new ScanResult(totals[0], totals[1], totals[2], 0, verdicts, patterns, issues);
    }

    private static void scanPatternInventory(Object owner, IGrid grid, Level level,
                                             List<PatternIssue> issues, List<Component> verdicts,
                                             List<ScannedPattern> patterns,
                                             List<DuplicateCandidate> duplicateCandidates,
                                             List<InputIssueCandidate> inputIssueCandidates,
                                             Set<AEKey> scannedCraftingOutputs,
                                             int[] totals, ScanContext context) {
        InternalInventory inv = PatternInventoryHelper.patternInventoryOf(owner);
        BlockEntity be = blockEntityOf(owner);
        if (inv == null || be == null) {
            return;
        }
        // Wireless containers (AE2LT overloaded providers) belong to the main
        // network they connect to, not to their own (possibly empty) grid.
        IGrid checkGrid = grid;
        if (level instanceof ServerLevel serverLevel) {
            IGrid resolved = WirelessHelper.resolveGrid(serverLevel, owner);
            if (resolved != null) {
                checkGrid = resolved;
            }
        }
        BlockPos pos = be.getBlockPos();
        boolean provider = isPatternProviderHost(owner);
        String location = describeContainer(owner, be, provider, pos);
        Map<AEItemKey, List<IPatternDetails>> providerDetails = new HashMap<>();
        if (owner instanceof PatternProviderLogicHost host) {
            try {
                for (IPatternDetails details : host.getLogic().getAvailablePatterns()) {
                    if (details != null && details.getDefinition() != null) {
                        providerDetails
                                .computeIfAbsent(details.getDefinition(), ignored -> new ArrayList<>())
                                .add(details);
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {
                // A stale provider cache must not prevent raw pattern decoding.
            }
        }
        for (int slot = 0; slot < inv.size(); slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            totals[0]++;
            if (provider) {
                totals[1]++;
            } else {
                totals[2]++;
            }
            IPatternDetails providerDetail = null;
            List<IPatternDetails> matchingDetails = providerDetails.get(AEItemKey.of(stack));
            if (matchingDetails != null && !matchingDetails.isEmpty()) {
                providerDetail = matchingDetails.removeFirst();
            }
            check(stack, providerDetail, checkGrid, level, location, pos, slot,
                    provider ? owner : null,
                    issues, verdicts, patterns, duplicateCandidates,
                    inputIssueCandidates, scannedCraftingOutputs, 1, context);
        }
    }

    private static String describeContainer(
            Object owner, BlockEntity blockEntity,
            boolean provider, BlockPos pos) {
        Component customName = null;
        Object ownerName = readMember(owner, "getCustomName");
        if (ownerName instanceof Component component) {
            customName = component;
        }
        if (customName == null && blockEntity != null) {
            customName = blockEntity.getBlockState().getBlock().getName();
        }
        if (customName == null) {
            customName = Component.translatable(
                    provider ? "patternchecker.location.provider"
                            : "patternchecker.location.container");
        }
        return customName.getString() + " [" + pos.toShortString() + "]";
    }

    /**
     * Pattern-provider parts are not block entities themselves. AE2 exposes
     * their cable-bus host through AEBasePart.
     */
    private static BlockEntity blockEntityOf(Object owner) {
        if (owner instanceof BlockEntity blockEntity) {
            return blockEntity;
        }
        if (owner instanceof PatternProviderLogicHost host) {
            return host.getBlockEntity();
        }
        if (owner instanceof AEBasePart part) {
            return part.getBlockEntity();
        }
        return null;
    }

    /**
     * AdvancedAE exposes the same provider-host shape without implementing
     * AE2's canonical PatternProviderLogicHost interface.
     */
    private static boolean isPatternProviderHost(Object owner) {
        return owner instanceof PatternProviderLogicHost
                || findNamedInterface(owner == null ? null : owner.getClass(),
                ADVANCED_AE_PROVIDER_HOST, new HashSet<>()) != null
                || isMekEnergisticsMachine(owner)
                || isAe2LtMatrixPort(owner)
                || isAe2LtPigmeePatternProvider(owner);
    }

    private static boolean isMekEnergisticsMachine(Object owner) {
        return findNamedInterface(owner == null ? null : owner.getClass(),
                MEK_ENERGISTICS_MACHINE, new HashSet<>()) != null;
    }

    private static boolean isAe2LtMatrixPort(Object owner) {
        return owner != null && owner.getClass().getName().equals(AE2LT_MATRIX_PORT);
    }

    private static List<Direction> providerTargets(Object host) {
        if (isAe2LtPigmeePatternProvider(host)) {
            return List.of(Direction.values());
        }
        if (host instanceof PatternProviderLogicHost provider) {
            return List.copyOf(provider.getTargets());
        }
        Object value = invokeInterfaceNoArg(host, ADVANCED_AE_PROVIDER_HOST, "getTargets");
        List<Direction> targets = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object entry : iterable) {
                if (entry instanceof Direction direction) {
                    targets.add(direction);
                }
            }
        } else if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object entry = java.lang.reflect.Array.get(value, i);
                if (entry instanceof Direction direction) {
                    targets.add(direction);
                }
            }
        }
        return targets;
    }

    private static boolean isAe2LtPigmeePatternProvider(Object owner) {
        return owner != null && owner.getClass().getName().equals(AE2LT_PIGMEE_PATTERN_PROVIDER);
    }

    private static Object invokeInterfaceNoArg(Object target, String interfaceName, String methodName) {
        Class<?> type = findNamedInterface(target == null ? null : target.getClass(),
                interfaceName, new HashSet<>());
        if (type == null) {
            return null;
        }
        try {
            return type.getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Class<?> findNamedInterface(Class<?> type, String interfaceName, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) {
            return null;
        }
        try {
            for (Class<?> iface : type.getInterfaces()) {
                if (iface.getName().equals(interfaceName)) {
                    return iface;
                }
                Class<?> nested = findNamedInterface(iface, interfaceName, visited);
                if (nested != null) {
                    return nested;
                }
            }
            return findNamedInterface(type.getSuperclass(), interfaceName, visited);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static void check(ItemStack stack, IPatternDetails providerDetail,
                              IGrid grid, Level level, String location, BlockPos pos,
                              int slot, Object host,
                              List<PatternIssue> allIssues, List<Component> verdicts,
                              List<ScannedPattern> patterns,
                              List<DuplicateCandidate> duplicateCandidates,
                              List<InputIssueCandidate> inputIssueCandidates,
                              Set<AEKey> scannedCraftingOutputs, long copies,
                              ScanContext context) {
        List<PatternIssue> issues = new ArrayList<>();
        Component patternName = stack.getHoverName();
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        Component outputDesc = Component.empty();
        Component inputDesc = Component.empty();

        // Explicitly flag blank/unencoded patterns sitting in provider slots.
        if (!PatternDetailsHelper.isEncodedPattern(stack)
                && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().contains("pattern")) {
            issues.add(new PatternIssue(PatternIssue.Type.ERROR, PatternIssue.Category.BROKEN,
                    message("patternchecker.issue.blankPattern", patternName, location, null), pos, location));
            verdicts.add(verdictLine("patternchecker.verdict.broken", patternName, location,
                    "blank/unencoded"));
            patterns.add(new ScannedPattern(itemId, patternName, outputDesc, inputDesc, location, pos, slot, issues));
            allIssues.addAll(issues);
            return;
        }

        // Decoding is AE2's own gate: it throws for missing content, removed
        // recipes or recipes that no longer match the encoded grid.
        IPatternDetails details;
        try {
            details = providerDetail != null
                    ? providerDetail
                    : PatternDetailsHelper.decodePattern(stack, level);
        } catch (Exception | LinkageError e) {
            String detail = e.getMessage();
            String key = detail != null && detail.toLowerCase().contains("missing content")
                    ? "patternchecker.issue.missingContent"
                    : "patternchecker.issue.invalid";
            MutableComponent message = message(key, patternName, location, detail);
            issues.add(new PatternIssue(PatternIssue.Type.ERROR, PatternIssue.Category.BROKEN, message, pos, location));
            verdicts.add(verdictLine("patternchecker.verdict.broken", patternName, location, e.getMessage()));
            patterns.add(new ScannedPattern(itemId, patternName, outputDesc, inputDesc,
                    location, pos, slot, issues));
            allIssues.addAll(issues);
            return;
        }
        if (details == null) {
            issues.add(new PatternIssue(PatternIssue.Type.ERROR, PatternIssue.Category.BROKEN,
                    message("patternchecker.issue.undecodable", patternName, location, null), pos, location));
            verdicts.add(verdictLine("patternchecker.verdict.broken", patternName, location, null));
            patterns.add(new ScannedPattern(itemId, patternName, outputDesc, inputDesc,
                    location, pos, slot, issues));
            allIssues.addAll(issues);
            return;
        }
        outputDesc = describeOutputs(details);
        inputDesc = describeInputs(details);

        boolean processing = isProcessingPattern(details);
        if (processing) {
            ProcessingMachineResult result = checkProcessingMachine(host, level, pos, details, context);
            if (result == ProcessingMachineResult.NO_RECIPE
                    || result == ProcessingMachineResult.WRONG_MACHINE) {
                logMachineMismatch(host, pos, details, result, context);
            }
            String verdictKey = switch (result) {
                case MATCH, UNKNOWN -> "patternchecker.verdict.processing.recipe";
                case NO_TARGET -> "patternchecker.verdict.processing.noTarget";
                case WRONG_MACHINE -> "patternchecker.verdict.processing.wrongMachine";
                case NO_RECIPE -> "patternchecker.verdict.processing.noRecipe";
            };
            verdicts.add(verdictLine(verdictKey, patternName, location, null));
            checkProcessingPattern(details, patternName, location, pos, issues, result);
        } else {
            addCraftingOutputs(details, scannedCraftingOutputs);
            // Decoding already validated that a crafting/stonecutting/smithing
            // recipe still exists and matches, so the pattern is craftable.
            verdicts.add(verdictLine("patternchecker.verdict.craftable", patternName, location, null));
        }

        if (details.getOutputs().isEmpty()) {
            issues.add(new PatternIssue(PatternIssue.Type.ERROR, PatternIssue.Category.BROKEN,
                    message("patternchecker.issue.nooutput", patternName, location, null), pos, location));
        }

        // Check that every input is stocked on the network or craftable.
        GridState gridState = context.gridState(grid);
        KeyCounter stored = gridState.stored();
        ICraftingService crafting = gridState.crafting();
        for (IPatternDetails.IInput input : details.getInputs()) {
            GenericStack[] possible = input.getPossibleInputs();
            if (possible == null || possible.length == 0) {
                issues.add(new PatternIssue(PatternIssue.Type.WARNING, PatternIssue.Category.INPUT,
                        message("patternchecker.issue.input.empty", patternName, location, null), pos, location));
                continue;
            }

            boolean obtainable = false;
            for (GenericStack candidate : possible) {
                if (candidate == null || candidate.what() == null) {
                    continue;
                }
                long required = Math.max(1, candidate.amount() * input.getMultiplier());
                if (stored.get(candidate.what()) >= required
                        || isCraftableInput(crafting, input, candidate.what(), level,
                        gridState.exactCraftable())) {
                    obtainable = true;
                    break;
                }
            }

            if (!obtainable) {
                MutableComponent missing = Component.empty();
                boolean first = true;
                for (GenericStack candidate : possible) {
                    if (candidate == null || candidate.what() == null) {
                        continue;
                    }
                    if (!first) {
                        missing.append(Component.literal(" / "));
                    }
                    missing.append(candidate.what().getDisplayName());
                    first = false;
                }
                PatternIssue missingIssue = new PatternIssue(
                        PatternIssue.Type.WARNING, PatternIssue.Category.INPUT,
                        Component.translatable("patternchecker.issue.input.missing", missing)
                                .append(Component.literal(" - "))
                                .append(patternName)
                                .append(Component.literal(" @ "))
                                .append(Component.literal(location)),
                        pos, location);
                issues.add(missingIssue);
                inputIssueCandidates.add(new InputIssueCandidate(missingIssue, issues, input));
            }
        }

        ScannedPattern scannedPattern =
                new ScannedPattern(itemId, patternName, outputDesc, inputDesc, location, pos, slot, issues);
        patterns.add(scannedPattern);
        duplicateCandidates.add(new DuplicateCandidate(
                duplicateSignature(details), scannedPattern, Math.max(1L, copies)));
        allIssues.addAll(issues);
    }

    private static boolean isProcessingPattern(IPatternDetails details) {
        Object current = details;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            if (current instanceof AEProcessingPattern) {
                return true;
            }
            current = firstMember(current, "wrappedPatternDetails");
        }
        return false;
    }

    private static boolean isCraftableInput(ICraftingService crafting, IPatternDetails.IInput input,
                                            AEKey key, Level level,
                                            Map<AEKey, Boolean> exactCraftable) {
        if (exactCraftable.computeIfAbsent(key, crafting::isCraftable)) {
            return true;
        }
        try {
            return crafting.getFuzzyCraftable(key, craftable -> input.isValid(craftable, level)) != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void addCraftingOutputs(IPatternDetails details, Set<AEKey> outputs) {
        for (GenericStack output : details.getOutputs()) {
            if (output != null && output.what() != null) {
                outputs.add(output.what());
            }
        }
    }

    private static void removeCraftableInputIssues(List<InputIssueCandidate> candidates,
                                                   Set<AEKey> craftingOutputs,
                                                   List<PatternIssue> allIssues, Level level) {
        if (craftingOutputs.isEmpty()) {
            return;
        }
        for (InputIssueCandidate candidate : candidates) {
            boolean craftable = false;
            for (AEKey output : craftingOutputs) {
                try {
                    if (candidate.input().isValid(output, level)) {
                        craftable = true;
                        break;
                    }
                } catch (RuntimeException ignored) {
                }
            }
            if (craftable) {
                candidate.ownerIssues().remove(candidate.issue());
                allIssues.remove(candidate.issue());
            }
        }
    }

    private static DuplicateSignature duplicateSignature(IPatternDetails details) {
        List<DuplicateInput> inputs = new ArrayList<>();
        for (IPatternDetails.IInput input : details.getInputs()) {
            List<GenericStack> possibleInputs = new ArrayList<>();
            GenericStack[] possible = input.getPossibleInputs();
            if (possible != null) {
                for (GenericStack candidate : possible) {
                    if (candidate != null) {
                        possibleInputs.add(candidate);
                    }
                }
            }
            inputs.add(new DuplicateInput(List.copyOf(possibleInputs), input.getMultiplier()));
        }

        List<GenericStack> outputs = new ArrayList<>();
        for (GenericStack output : details.getOutputs()) {
            if (output != null) {
                outputs.add(output);
            }
        }
        return new DuplicateSignature(
                details.getClass().getName(), List.copyOf(inputs), List.copyOf(outputs));
    }

    private static void markDuplicatePatterns(List<DuplicateCandidate> candidates,
                                              List<PatternIssue> allIssues) {
        Map<DuplicateSignature, List<DuplicateCandidate>> groups = new LinkedHashMap<>();
        for (DuplicateCandidate candidate : candidates) {
            groups.computeIfAbsent(candidate.signature(), ignored -> new ArrayList<>()).add(candidate);
        }

        for (List<DuplicateCandidate> group : groups.values()) {
            long totalCopies = 0;
            for (DuplicateCandidate candidate : group) {
                totalCopies = saturatedAdd(totalCopies, candidate.copies());
            }
            if (totalCopies < 2) {
                continue;
            }

            for (DuplicateCandidate candidate : group) {
                ScannedPattern pattern = candidate.pattern();
                PatternIssue issue = new PatternIssue(
                        PatternIssue.Type.WARNING,
                        PatternIssue.Category.DUPLICATE,
                        duplicateMessage(totalCopies, pattern),
                        pattern.pos(),
                        pattern.location());
                pattern.issues().add(issue);
                allIssues.add(issue);
            }
        }
    }

    private static MutableComponent duplicateMessage(long copies, ScannedPattern pattern) {
        return Component.translatable("patternchecker.issue.duplicate", copies)
                .append(Component.literal(" - "))
                .append(pattern.name())
                .append(Component.literal(" @ "))
                .append(Component.literal(pattern.location()));
    }

    private static int saturatedAdd(int value, long amount) {
        return (int) Math.min(Integer.MAX_VALUE, (long) value + Math.max(0L, amount));
    }

    private static long saturatedAdd(long value, long amount) {
        long positiveAmount = Math.max(0L, amount);
        return Long.MAX_VALUE - value < positiveAmount ? Long.MAX_VALUE : value + positiveAmount;
    }

    private static Component describeOutputs(IPatternDetails details) {
        MutableComponent desc = Component.empty();
        int count = 0;
        for (GenericStack output : details.getOutputs()) {
            if (output == null || output.what() == null) {
                continue;
            }
            if (count > 0) {
                desc.append(Component.literal("、"));
            }
            desc.append(output.what().getDisplayName()).append(Component.literal(" ×" + output.amount()));
            if (++count >= 2) {
                break;
            }
        }
        return desc;
    }

    private static Component describeInputs(IPatternDetails details) {
        MutableComponent desc = Component.empty();
        int count = 0;
        for (IPatternDetails.IInput input : details.getInputs()) {
            GenericStack[] possible = input.getPossibleInputs();
            if (possible == null || possible.length == 0 || possible[0] == null || possible[0].what() == null) {
                continue;
            }
            if (count > 0) {
                desc.append(Component.literal("、"));
            }
            long amount = Math.max(1, possible[0].amount() * input.getMultiplier());
            desc.append(possible[0].what().getDisplayName()).append(Component.literal(" ×" + amount));
            if (++count >= 3) {
                break;
            }
        }
        return desc;
    }

    private static void checkProcessingPattern(IPatternDetails details, Component patternName, String location,
                                               BlockPos pos, List<PatternIssue> issues,
                                               ProcessingMachineResult machineResult) {
        String issueKey = switch (machineResult) {
            case NO_TARGET -> "patternchecker.issue.processing.noTarget";
            case WRONG_MACHINE -> "patternchecker.issue.processing.wrongMachine";
            case NO_RECIPE -> "patternchecker.issue.processing.noRecipe";
            case MATCH, UNKNOWN -> null;
        };
        if (issueKey != null) {
            PatternIssue.Type issueType = machineResult == ProcessingMachineResult.NO_TARGET
                    ? PatternIssue.Type.WARNING
                    : PatternIssue.Type.ERROR;
            PatternIssue.Category category = machineResult == ProcessingMachineResult.NO_RECIPE
                    ? PatternIssue.Category.BROKEN
                    : PatternIssue.Category.MACHINE;
            issues.add(new PatternIssue(issueType, category,
                    message(issueKey, patternName, location, null), pos, location));
        }

        // Input/output amounts must be positive.
        for (IPatternDetails.IInput input : details.getInputs()) {
            for (GenericStack candidate : input.getPossibleInputs()) {
                if (candidate != null && candidate.amount() <= 0) {
                    issues.add(new PatternIssue(PatternIssue.Type.ERROR, PatternIssue.Category.BROKEN,
                            message("patternchecker.issue.processing.zeroInput", patternName, location, null),
                            pos, location));
                }
            }
        }
        for (GenericStack output : details.getOutputs()) {
            if (output == null || output.amount() <= 0) {
                issues.add(new PatternIssue(PatternIssue.Type.ERROR, PatternIssue.Category.BROKEN,
                        message("patternchecker.issue.processing.zeroOutput", patternName, location, null),
                        pos, location));
            }
        }

        // An output identical to an input usually means a misconfigured loop.
        Set<AEKey> inputKeys = new HashSet<>();
        for (IPatternDetails.IInput input : details.getInputs()) {
            for (GenericStack candidate : input.getPossibleInputs()) {
                if (candidate != null && candidate.what() != null) {
                    inputKeys.add(candidate.what());
                }
            }
        }
        for (GenericStack output : details.getOutputs()) {
            if (output != null && output.what() != null && inputKeys.contains(output.what())) {
                issues.add(new PatternIssue(PatternIssue.Type.WARNING, PatternIssue.Category.BROKEN,
                        message("patternchecker.issue.processing.selfLoop", patternName, location, null),
                        pos, location));
                break;
            }
        }

    }

    /**
     * Looks through the installed recipe registry for a non-crafting recipe
     * whose ingredients are satisfied by the pattern's inputs and whose result
     * matches one of the pattern's outputs.
     */
    private static boolean hasMatchingMachineRecipe(Level level, IPatternDetails details, RecipeType<?> onlyType,
                                                    String machineId, ScanContext context) {
        List<PatternInputSlot> inputs = patternInputSlots(details);
        if (inputs.isEmpty() || details.getOutputs().isEmpty()) {
            return false;
        }

        DuplicateSignature signature = duplicateSignature(details);
        RecipeMatchKey cacheKey = new RecipeMatchKey(signature, onlyType, machineId);
        Boolean cached = context.machineRecipeMatches.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Set<PreparedRecipe> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
        for (GenericStack output : details.getOutputs()) {
            if (output != null && output.what() instanceof AEKey outputKey) {
                candidates.addAll(context.machineRecipesFor(outputKey.getId().toString()));
            }
        }
        for (PreparedRecipe recipe : candidates) {
            if (onlyType != null && recipe.type() != onlyType) {
                continue;
            }
            if (machineId != null && !machineId.equals(recipe.machineId())) {
                continue;
            }
            for (long scale : matchingRecipeScales(
                    details, recipe.outputs(), recipe.requireAllOutputs())) {
                if (matchesRecipeInputsExactly(recipe.requirements(), inputs, scale)) {
                    context.machineRecipeMatches.put(cacheKey, true);
                    return true;
                }
            }
        }
        context.machineRecipeMatches.put(cacheKey, false);
        return false;
    }

    private static boolean matchesRequirement(RecipeRequirement requirement, PatternInputCandidate input) {
        if (requirement.ingredient() != null) {
            return input.itemStack() != null && requirement.ingredient().test(input.itemStack());
        }
        return requirement.identifiers().contains(input.identifier());
    }

    /**
     * Processing patterns may scale one recipe operation by an integer factor.
     * Every encoded output must belong to the same recipe and imply the same
     * scale. Recipes may expose optional/chance outputs that are not encoded,
     * so the reverse (every recipe output must be present) is intentionally not
     * required.
     */
    private static Set<Long> matchingRecipeScales(IPatternDetails details,
                                                  List<RecipeOutput> recipeOutputs,
                                                  boolean requireAllOutputs) {
        Map<String, Long> patternAmounts = new LinkedHashMap<>();
        for (GenericStack output : details.getOutputs()) {
            if (output == null || !(output.what() instanceof AEKey key) || output.amount() <= 0) {
                return Set.of();
            }
            patternAmounts.merge(key.getId().toString(), output.amount(), PatternScanner::saturatedAdd);
        }
        Map<String, Long> recipeAmounts = new LinkedHashMap<>();
        for (RecipeOutput output : recipeOutputs) {
            recipeAmounts.merge(output.identifier(), Math.max(1L, output.amount()),
                    PatternScanner::saturatedAdd);
        }
        if (requireAllOutputs && !patternAmounts.keySet().equals(recipeAmounts.keySet())) {
            return Set.of();
        }

        Set<Long> scales = null;
        for (Map.Entry<String, Long> patternOutput : patternAmounts.entrySet()) {
            Long recipeAmount = recipeAmounts.get(patternOutput.getKey());
            if (recipeAmount == null || recipeAmount <= 0
                    || patternOutput.getValue() % recipeAmount != 0) {
                return Set.of();
            }
            long scale = patternOutput.getValue() / recipeAmount;
            if (scale <= 0) {
                return Set.of();
            }
            if (scales == null) {
                scales = new HashSet<>(Set.of(scale));
            } else if (!scales.contains(scale)) {
                return Set.of();
            }
        }
        return scales == null ? Set.of() : scales;
    }

    private static boolean requiresAllRecipeOutputs(Recipe<?> recipe) {
        return switch (recipeTypeId(recipe.getType())) {
            case "ae2lt:lightning_transform",
                    "ae2lt:firmament_conversion",
                    "ae2lt:lightning_simulation",
                    "ae2lt:lightning_assembly",
                    "ae2lt:overload_processing",
                    "ae2lt:crystal_catalyzer" -> true;
            default -> false;
        };
    }

    /**
     * Matches recipe requirements against AE input slots without flattening
     * substitution candidates. A slot chooses one candidate identity and its
     * capacity can only be consumed once across all requirements. At the end
     * no encoded input may remain unused.
     */
    private static boolean matchesRecipeInputsExactly(List<RecipeRequirement> requirements,
                                                      List<PatternInputSlot> slots, long scale) {
        if (requirements.isEmpty()) {
            return slots.isEmpty();
        }
        List<RecipeRequirement> scaled = new ArrayList<>(requirements.size());
        try {
            for (RecipeRequirement requirement : requirements) {
                scaled.add(new RecipeRequirement(requirement.ingredient(), requirement.identifiers(),
                        Math.multiplyExact(Math.max(1L, requirement.amount()), scale)));
            }
        } catch (ArithmeticException ignored) {
            return false;
        }
        scaled.sort(java.util.Comparator.comparingInt(
                requirement -> compatibleSlotCount(requirement, slots)));
        int[] chosenCandidates = new int[slots.size()];
        java.util.Arrays.fill(chosenCandidates, -1);
        long[] remaining = new long[slots.size()];
        return matchRequirementAt(0, scaled, slots, chosenCandidates, remaining);
    }

    private static int compatibleSlotCount(RecipeRequirement requirement, List<PatternInputSlot> slots) {
        int count = 0;
        for (PatternInputSlot slot : slots) {
            for (PatternInputCandidate candidate : slot.candidates()) {
                if (matchesRequirement(requirement, candidate)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private static boolean matchRequirementAt(int requirementIndex,
                                              List<RecipeRequirement> requirements,
                                              List<PatternInputSlot> slots,
                                              int[] chosenCandidates, long[] remaining) {
        if (requirementIndex >= requirements.size()) {
            for (int i = 0; i < slots.size(); i++) {
                if (chosenCandidates[i] < 0 || remaining[i] != 0) {
                    return false;
                }
            }
            return true;
        }
        return consumeRequirement(requirementIndex, requirements.get(requirementIndex).amount(), 0,
                requirements, slots, chosenCandidates, remaining);
    }

    private static boolean consumeRequirement(int requirementIndex, long needed, int firstSlot,
                                              List<RecipeRequirement> requirements,
                                              List<PatternInputSlot> slots,
                                              int[] chosenCandidates, long[] remaining) {
        if (needed == 0) {
            return matchRequirementAt(requirementIndex + 1, requirements, slots,
                    chosenCandidates, remaining);
        }
        RecipeRequirement requirement = requirements.get(requirementIndex);
        for (int slotIndex = firstSlot; slotIndex < slots.size(); slotIndex++) {
            PatternInputSlot slot = slots.get(slotIndex);
            int chosen = chosenCandidates[slotIndex];
            if (chosen >= 0) {
                PatternInputCandidate candidate = slot.candidates().get(chosen);
                if (!matchesRequirement(requirement, candidate) || remaining[slotIndex] <= 0) {
                    continue;
                }
                long consumed = Math.min(needed, remaining[slotIndex]);
                remaining[slotIndex] -= consumed;
                if (consumeRequirement(requirementIndex, needed - consumed, slotIndex + 1,
                        requirements, slots, chosenCandidates, remaining)) {
                    return true;
                }
                remaining[slotIndex] += consumed;
                continue;
            }

            for (int candidateIndex = 0; candidateIndex < slot.candidates().size(); candidateIndex++) {
                PatternInputCandidate candidate = slot.candidates().get(candidateIndex);
                if (!matchesRequirement(requirement, candidate) || candidate.amount() <= 0) {
                    continue;
                }
                chosenCandidates[slotIndex] = candidateIndex;
                long consumed = Math.min(needed, candidate.amount());
                remaining[slotIndex] = candidate.amount() - consumed;
                if (consumeRequirement(requirementIndex, needed - consumed, slotIndex + 1,
                        requirements, slots, chosenCandidates, remaining)) {
                    return true;
                }
                chosenCandidates[slotIndex] = -1;
                remaining[slotIndex] = 0;
            }
        }
        return false;
    }

    private record MmrRecipeData(List<RecipeRequirement> requirements,
                                 List<RecipeOutput> outputs, String machineId) {
    }

    /**
     * Modular Machinery Reborn recipes deliberately expose an empty vanilla
     * result stack. Their real item/fluid inputs and outputs are requirement
     * objects, so they must be unpacked before the generic recipe index can see
     * them.
     */
    private static MmrRecipeData mmrRecipeData(Recipe<?> recipe) {
        if (!MMR_RECIPE_TYPE.equals(recipeTypeId(recipe.getType()))) {
            return null;
        }

        List<RecipeRequirement> requirements = new ArrayList<>();
        List<RecipeOutput> outputs = new ArrayList<>();
        Object rawRequirements = readMember(recipe, "getRequirements");
        if (rawRequirements instanceof Iterable<?> iterable) {
            for (Object wrapper : iterable) {
                Object requirement = readMember(wrapper, "requirement");
                if (requirement == null) {
                    continue;
                }
                Object mode = readMember(requirement, "getMode");
                Object ingredient = readMember(requirement, "getIngredient");
                if (mode == null || ingredient == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(readMember(mode, "isInput"))) {
                    RecipeRequirement input = sizedIngredientFrom(ingredient);
                    if (input != null) {
                        requirements.add(input);
                    }
                } else if (Boolean.TRUE.equals(readMember(mode, "isOutput"))) {
                    addSizedRecipeOutputs(outputs, ingredient);
                }
            }
        }

        String machineId = resourceIdentifier(readMember(recipe, "getOwningMachineIdentifier"));
        return new MmrRecipeData(requirements, outputs, machineId);
    }

    private static void addSizedRecipeOutputs(List<RecipeOutput> outputs, Object sizedIngredient) {
        long amount = numericAmount(sizedIngredient, 1L);
        Object representations = invokeNoArg(sizedIngredient,
                "getItems", "getFluids", "getStacks", "getRepresentations");
        if (representations instanceof Iterable<?> iterable) {
            for (Object representation : iterable) {
                String identifier = resourceIdentifier(representation);
                if (identifier != null) {
                    addUniqueRecipeOutput(outputs, identifier, amount);
                }
            }
        } else if (representations != null && representations.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(representations);
            for (int i = 0; i < length; i++) {
                String identifier = resourceIdentifier(java.lang.reflect.Array.get(representations, i));
                if (identifier != null) {
                    addUniqueRecipeOutput(outputs, identifier, amount);
                }
            }
        }
    }

    private static void logMachineMismatch(Object host, BlockPos pos, IPatternDetails details,
                                           ProcessingMachineResult result, ScanContext context) {
        if (host == null || pos == null) {
            return;
        }
        String diagnosticKey = pos.asLong() + ":" + result + ":" + duplicateSignature(details);
        if (!context.loggedMachineMismatches.add(diagnosticKey)) {
            return;
        }
        MachineState machine = context.machineState(host, pos);
        List<String> recipeTypes = machine.types().stream()
                .map(PatternScanner::recipeTypeId)
                .sorted()
                .toList();
        PatternCheckerMod.LOGGER.info(
                "Pattern validation {} at provider {}: targets={}, recipeTypes={}, mmrMachines={}, inputs={}, outputs={}",
                result, pos.toShortString(), machine.targetBlockIds(), recipeTypes,
                machine.machineIds(), describePatternInputsForLog(details),
                describePatternOutputsForLog(details));
    }

    private static List<String> describePatternInputsForLog(IPatternDetails details) {
        List<String> inputs = new ArrayList<>();
        for (IPatternDetails.IInput input : details.getInputs()) {
            List<String> candidates = new ArrayList<>();
            for (GenericStack candidate : input.getPossibleInputs()) {
                if (candidate != null && candidate.what() != null) {
                    candidates.add(candidate.what().getId() + "@" +
                            Math.max(1L, candidate.amount() * input.getMultiplier()));
                }
            }
            inputs.add(String.join("|", candidates));
        }
        return inputs;
    }

    private static List<String> describePatternOutputsForLog(IPatternDetails details) {
        List<String> outputs = new ArrayList<>();
        for (GenericStack output : details.getOutputs()) {
            if (output != null && output.what() != null) {
                outputs.add(output.what().getId() + "@" + output.amount());
            }
        }
        return outputs;
    }

    private static String recipeTypeId(RecipeType<?> type) {
        ResourceLocation id = BuiltInRegistries.RECIPE_TYPE.getKey(type);
        return id == null ? "" : id.toString();
    }

    /**
     * Uses stable Recipe API methods for unknown recipe implementations and a
     * namespace-scoped adapter for optional mods. Member aliases never cross a
     * mod boundary, so adding support for one addon cannot reinterpret another
     * addon's recipe object.
     */
    private static List<RecipeOutput> recipeOutputs(
            Recipe<?> recipe, net.minecraft.core.HolderLookup.Provider registryAccess) {
        String namespace = recipeNamespace(recipe);
        return switch (namespace) {
            case "mekanism" -> recipeOutputsFromMembers(recipe, registryAccess,
                    aliases("getOutputDefinition", "outputDefinition"),
                    aliases("getMainOutputDefinition", "mainOutputDefinition"),
                    aliases("getSecondaryOutputDefinition", "secondaryOutputDefinition"),
                    aliases("getChemicalOutputDefinition", "chemicalOutputDefinition"),
                    aliases("getFluidOutputDefinition", "fluidOutputDefinition"),
                    aliases("getOutput", "output"),
                    aliases("getMainOutput", "mainOutput"),
                    aliases("getSecondaryOutput", "secondaryOutput"),
                    aliases("getMaxSecondaryOutput", "maxSecondaryOutput"));
            case "ae2" -> recipeOutputsFromMembers(recipe, registryAccess,
                    aliases("getResult", "result"), aliases("getOutput", "output"));
            case "advanced_ae" -> recipeOutputsFromMembers(recipe, registryAccess,
                    aliases("getOutput", "output"));
            case "extendedae" -> recipeOutputsFromMembers(recipe, registryAccess,
                    aliases("getOutput", "output"));
            case "ae2lt" -> ae2LtRecipeOutputs(recipe, registryAccess);
            case "data_energistics" -> recipeOutputsFromMembers(recipe, registryAccess,
                    aliases("getResult", "result"), aliases("getResults", "results"),
                    aliases("getItemOutputs", "itemOutputs"),
                    aliases("getCraftedItemOutputs", "craftedItemOutputs"),
                    aliases("getFluidOutputs", "fluidOutputs"),
                    aliases("getKeyOutput", "keyOutput"));
            case "neoecoae" -> recipeOutputsFromMembers(recipe, registryAccess,
                    aliases("itemOutput", "getItemOutput"),
                    aliases("fluidOutput", "getFluidOutput"),
                    aliases("output", "getOutput"));
            case "ae2cs" -> recipeOutputsFromMembers(recipe, registryAccess,
                    aliases("result", "getResult"));
            case "industrialforegoing" -> recipeOutputsFromMembers(recipe, registryAccess,
                    aliases("getOutput", "output"), aliases("getResult", "result"),
                    aliases("getOutputState", "outputState"),
                    aliases("getFluidOutput", "fluidOutput", "outputFluid"));
            case "ifeu" -> recipeOutputsFromMembers(recipe, registryAccess,
                    aliases("output", "getOutput"));
            case "productivebees", "resourcefulbees", "beesourceful" ->
                    recipeOutputsFromMembers(recipe, registryAccess,
                            aliases("getOutputs", "outputs"),
                            aliases("getRecipeOutputs", "recipeOutputs"),
                            aliases("getOutput", "output"),
                            aliases("getFluidOutput", "getFluidOutputs", "fluidOutput"));
            case "justdynathings", "jdte", "justdirethings" ->
                    jdteAndJdtRecipeOutputs(recipe, registryAccess);
            case "enderio" -> enderIoRecipeOutputs(recipe, registryAccess);
            default -> genericRecipeOutputs(recipe, registryAccess);
        };
    }

    /**
     * AE2 Lightning Technology 2.0.7 has seven unrelated recipe models. Keep
     * their layouts separate instead of sharing a broad addon-wide alias list.
     */
    private static List<RecipeOutput> ae2LtRecipeOutputs(
            Recipe<?> recipe, net.minecraft.core.HolderLookup.Provider registryAccess) {
        return switch (recipeTypeId(recipe.getType())) {
            case "ae2lt:lightning_transform" ->
                    genericRecipeOutputs(recipe, registryAccess);
            case "ae2lt:firmament_conversion" ->
                    recipeOutputsFromMembers(recipe, registryAccess,
                            aliases("getResultStacks", "getResultStack"));
            case "ae2lt:lightning_simulation", "ae2lt:lightning_assembly" ->
                    recipeOutputsFromMembers(recipe, registryAccess,
                            aliases("getResultStack"));
            case "ae2lt:overload_processing" ->
                    recipeOutputsFromMembers(recipe, registryAccess,
                            aliases("itemResults"), aliases("fluidResult"));
            case "ae2lt:crystal_catalyzer" ->
                    ae2LtCrystalCatalyzerOutputs(recipe, registryAccess);
            case "ae2lt:lightning_strike" ->
                    recipeOutputsFromMembers(recipe, registryAccess,
                            aliases("centerOutput"));
            default -> genericRecipeOutputs(recipe, registryAccess);
        };
    }

    private static List<RecipeOutput> ae2LtCrystalCatalyzerOutputs(
            Recipe<?> recipe, net.minecraft.core.HolderLookup.Provider registryAccess) {
        List<RecipeOutput> outputs = genericRecipeOutputs(recipe, registryAccess);
        Object outputSpec = firstMember(recipe, "outputSpec");
        Object resolved = firstMember(outputSpec, "resolve");
        addRecipeOutput(outputs, resolved);
        addRecipeOutput(outputs, firstMember(recipe, "getOutputTemplate"));
        return outputs;
    }

    private static List<RecipeOutput> jdteAndJdtRecipeOutputs(
            Recipe<?> recipe, net.minecraft.core.HolderLookup.Provider registryAccess) {
        List<RecipeOutput> outputs = recipeOutputsFromMembers(recipe, registryAccess,
                            aliases("getOutputs", "outputs"),
                            aliases("getOutput", "output"), aliases("getResult", "result"),
                            aliases("getOutputState", "outputState"),
                            aliases("centerOutput", "getCenterOutput"),
                            aliases("getFluidOutput", "fluidOutput", "outputFluid"));
        if ("jdte:bio_factory".equals(recipeTypeId(recipe.getType()))) {
            addRecipeOutputWithSeparateAmount(outputs,
                    firstMember(recipe, "outputFluid", "getOutputFluid"),
                    numericAmount(firstMember(recipe, "outputFluidAmount", "getOutputFluidAmount"), 1L));
        }
        return outputs;
    }

    private static String[] aliases(String... names) {
        return names;
    }

    private static String recipeNamespace(Recipe<?> recipe) {
        ResourceLocation id = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        return id == null ? "" : id.getNamespace();
    }

    private static List<RecipeOutput> genericRecipeOutputs(
            Recipe<?> recipe, net.minecraft.core.HolderLookup.Provider registryAccess) {
        List<RecipeOutput> outputs = new ArrayList<>();
        addRecipeOutput(outputs, recipe.getResultItem(registryAccess));
        return outputs;
    }

    private static List<RecipeOutput> recipeOutputsFromMembers(
            Recipe<?> recipe, net.minecraft.core.HolderLookup.Provider registryAccess,
            String[]... memberAliases) {
        List<RecipeOutput> outputs = genericRecipeOutputs(recipe, registryAccess);
        for (String[] aliases : memberAliases) {
            addRecipeOutput(outputs, firstMember(recipe, aliases));
        }
        return outputs;
    }

    private static List<RecipeOutput> enderIoRecipeOutputs(
            Recipe<?> recipe, net.minecraft.core.HolderLookup.Provider registryAccess) {
        List<RecipeOutput> outputs = recipeOutputsFromMembers(recipe, registryAccess,
                aliases("getOutput", "output"), aliases("getResult", "result"));
        try {
            Method getResultStacks = recipe.getClass().getMethod("getResultStacks", net.minecraft.core.RegistryAccess.class);
            addRecipeOutput(outputs, getResultStacks.invoke(recipe, registryAccess));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
        return outputs;
    }

    private static void addRecipeOutput(List<RecipeOutput> outputs, Object value) {
        addRecipeOutput(outputs, value, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static void addRecipeOutput(List<RecipeOutput> outputs, Object value, Set<Object> visited) {
        if (value == null || !visited.add(value)) {
            return;
        }
        if (value instanceof ItemStack stack && stack.isEmpty()) {
            return;
        }
        if (value instanceof net.neoforged.neoforge.fluids.FluidStack stack
                && stack.isEmpty()) {
            return;
        }
        if (value instanceof java.util.Optional<?> optional) {
            optional.ifPresent(entry -> addRecipeOutput(outputs, entry, visited));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                addRecipeOutput(outputs, entry.getKey(), visited);
                addRecipeOutput(outputs, entry.getValue(), visited);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object entry : iterable) {
                addRecipeOutput(outputs, entry, visited);
            }
            return;
        }
        if (value instanceof java.util.stream.Stream<?> stream) {
            stream.forEach(entry -> addRecipeOutput(outputs, entry, visited));
            return;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                addRecipeOutput(outputs, java.lang.reflect.Array.get(value, i), visited);
            }
            return;
        }
        if (value instanceof Ingredient ingredient) {
            for (ItemStack stack : ingredient.getItems()) {
                addRecipeOutput(outputs, stack, visited);
            }
            return;
        }
        if (value instanceof GenericStack stack) {
            if (stack.what() != null) {
                addUniqueRecipeOutput(outputs, stack.what().getId().toString(), stack.amount());
            }
            return;
        }
        if (value.getClass().isRecord()) {
            try {
                for (java.lang.reflect.RecordComponent component : value.getClass().getRecordComponents()) {
                    addRecipeOutput(outputs, component.getAccessor().invoke(value), visited);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            }
            return;
        }

        // NeoForge BlockTagIngredient and similar custom ingredients expose
        // their concrete item representations through getItems(). Resolve
        // those before falling back to parsing the wrapper's text form.
        Object representedItems = readMember(value, "getItems");
        if (representedItems != null && representedItems != value) {
            int previousSize = outputs.size();
            addRecipeOutput(outputs, representedItems, visited);
            if (outputs.size() > previousSize) {
                return;
            }
        }

        ItemStack itemStack = outputStackFrom(value);
        String identifier = itemStack.isEmpty()
                ? resourceIdentifier(value)
                : BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString();
        if (identifier != null && !identifier.isEmpty()) {
            long amount = itemStack.isEmpty() ? numericAmount(value, 1L) : itemStack.getCount();
            addUniqueRecipeOutput(outputs, identifier, amount);
            return;
        }

        // Wrapper/composite outputs that are not records (SizedFluidIngredient,
        // Ender IO OutputStack variants, and similar optional integrations).
        for (String member : new String[]{
                "getItemStack", "itemStack", "getStack", "stack", "getItem", "item",
                "getItems", "items",
                "getMainOutput", "mainOutput", "getMaxSecondaryOutput", "maxSecondaryOutput",
                "getSecondaryOutput", "secondaryOutput", "getFluid", "fluid", "getFluids",
                "fluids", "getChemical", "chemical", "resolve", "left", "right"
        }) {
            Object nested = readMember(value, member);
            if (nested != null && nested != value) {
                addRecipeOutput(outputs, nested, visited);
            }
        }
    }

    private static void addUniqueRecipeOutput(List<RecipeOutput> outputs, String identifier, long amount) {
        RecipeOutput candidate = new RecipeOutput(identifier, Math.max(1L, amount));
        for (int i = 0; i < outputs.size(); i++) {
            RecipeOutput existing = outputs.get(i);
            if (existing.identifier().equals(candidate.identifier())) {
                if (candidate.amount() > existing.amount()) {
                    outputs.set(i, candidate);
                }
                return;
            }
        }
        outputs.add(candidate);
    }

    private static void addRecipeOutputWithSeparateAmount(
            List<RecipeOutput> outputs, Object value, long amount) {
        if (value instanceof java.util.Optional<?> optional) {
            value = optional.orElse(null);
        }
        String identifier = resourceIdentifier(value);
        if (identifier != null) {
            addUniqueRecipeOutput(outputs, identifier, amount);
        }
    }

    /**
     * Unwraps an Ender IO OutputStack (getItem()) or a plain ItemStack.
     */
    private static ItemStack outputStackFrom(Object stack) {
        if (stack instanceof ItemStack itemStack) {
            return itemStack;
        }
        for (String member : new String[]{"getItemStack", "itemStack", "getStack", "stack", "getItem", "item"}) {
            Object result = readMember(stack, member);
            if (result instanceof ItemStack itemStack) {
                return itemStack;
            }
        }
        return ItemStack.EMPTY;
    }

    private record PatternInputCandidate(String identifier, ItemStack itemStack, long amount) {
    }

    private record PatternInputSlot(List<PatternInputCandidate> candidates) {
    }

    private record RecipeRequirement(Ingredient ingredient, Set<String> identifiers, long amount) {
    }

    private record RecipeOutput(String identifier, long amount) {
    }

    private static List<PatternInputSlot> patternInputSlots(IPatternDetails details) {
        List<PatternInputSlot> inputs = new ArrayList<>();
        for (IPatternDetails.IInput input : details.getInputs()) {
            List<PatternInputCandidate> candidates = new ArrayList<>();
            for (GenericStack candidate : input.getPossibleInputs()) {
                if (candidate != null && candidate.what() instanceof AEKey key) {
                    ItemStack itemStack = key instanceof AEItemKey itemKey ? itemKey.toStack() : null;
                    candidates.add(new PatternInputCandidate(key.getId().toString(), itemStack,
                            Math.max(1, candidate.amount() * input.getMultiplier())));
                }
            }
            if (!candidates.isEmpty()) {
                inputs.add(new PatternInputSlot(List.copyOf(candidates)));
            }
        }
        return inputs;
    }

    /**
     * Extracts inputs through namespace-scoped adapters. The generic fallback
     * only consumes Recipe#getIngredients(), which is the stable cross-mod
     * interface and cannot accidentally bind to unrelated fields.
     */
    private static List<RecipeRequirement> recipeRequirements(Recipe<?> recipe) {
        String typeId = recipeTypeId(recipe.getType());
        if (recipeNamespace(recipe).equals("ae2lt")) {
            return ae2LtRecipeRequirements(recipe);
        }
        if (typeId.equals("jdte:bio_factory")) {
            return bioFactoryRequirements(recipe);
        }
        return switch (recipeNamespace(recipe)) {
            case "mekanism" -> recipeRequirementsFromMembers(recipe,
                    aliases("getInput", "input"),
                    aliases("getInputA", "inputA"), aliases("getInputB", "inputB"),
                    aliases("getLeftInput", "leftInput"), aliases("getRightInput", "rightInput"),
                    aliases("getMainInput", "mainInput"), aliases("getExtraInput", "extraInput"),
                    aliases("getItemInput", "itemInput"),
                    aliases("getFluidInput", "fluidInput"),
                    aliases("getChemicalInput", "chemicalInput"),
                    aliases("getInputSolid", "inputSolid"),
                    aliases("getInputFluid", "inputFluid"),
                    aliases("getInputChemical", "inputChemical"));
            case "ae2" -> recipeRequirementsFromMembers(recipe,
                    aliases("getInput", "input"), aliases("getInputs", "inputs"),
                    aliases("getTopInput", "topInput"), aliases("getMiddleInput", "middleInput"),
                    aliases("getBottomInput", "bottomInput"));
            case "advanced_ae" -> recipeRequirementsFromMembers(recipe,
                    aliases("getInputs", "inputs"), aliases("getFluid", "fluid"));
            case "extendedae" -> recipeRequirementsFromMembers(recipe,
                    aliases("getInputs", "inputs"), aliases("getInput", "input"),
                    aliases("getFluid", "fluid"), aliases("getFuel", "fuel"));
            case "data_energistics" -> recipeRequirementsFromMembers(recipe,
                    aliases("getIngredient", "ingredient"),
                    aliases("getItemInputs", "itemInputs"),
                    aliases("getFluidInputs", "fluidInputs"),
                    aliases("getKeyInput", "keyInput"));
            case "neoecoae" -> recipeRequirementsFromMembers(recipe,
                    aliases("inputItems", "getInputItems"),
                    aliases("inputFluid", "getInputFluid"),
                    aliases("input", "getInput"));
            case "ae2cs" -> recipeRequirementsFromMembers(recipe,
                    aliases("inputA", "getInputA"), aliases("inputB", "getInputB"),
                    aliases("inputC", "getInputC"), aliases("input", "getInput"));
            case "industrialforegoing" -> recipeRequirementsFromMembers(recipe,
                    aliases("getInputs", "inputs"), aliases("getInput", "input"),
                    aliases("getIngredient", "ingredient"),
                    aliases("getFluidInput", "fluidInput", "inputFluid"),
                    aliases("getCatalyst", "catalyst"));
            case "ifeu" -> ifeuRecipeRequirements(recipe);
            case "draconicevolution" -> draconicFusionRequirements(recipe);
            case "productivebees", "resourcefulbees", "beesourceful" ->
                    recipeRequirementsFromMembers(recipe,
                            aliases("getInputs", "inputs"), aliases("getInput", "input"),
                            aliases("getItemInput", "itemInput"),
                            aliases("getIngredient", "ingredient"),
                            aliases("getFluidInput", "fluidInput", "fluidIngredient"));
            case "justdynathings", "jdte", "justdirethings" ->
                    recipeRequirementsFromMembers(recipe,
                            aliases("getInputs", "inputs"), aliases("getInput", "input"),
                            aliases("getIngredient", "ingredient"),
                            aliases("getSpecimen", "specimen"), aliases("getSeed", "seed"),
                            aliases("getCatalyst", "catalyst"),
                            aliases("getInputState", "inputState"),
                            aliases("getLeftInput", "leftInput"),
                            aliases("getRightInput", "rightInput"),
                            aliases("getCenterInput", "centerInput"),
                            aliases("getFluidInput", "fluidInput"),
                            aliases("getFluidInputs", "fluidInputs"));
            case "enderio" -> enderIoRecipeRequirements(recipe);
            default -> genericRecipeRequirements(recipe);
        };
    }

    private static List<RecipeRequirement> ae2LtRecipeRequirements(Recipe<?> recipe) {
        return switch (recipeTypeId(recipe.getType())) {
            case "ae2lt:lightning_transform",
                    "ae2lt:firmament_conversion",
                    "ae2lt:lightning_simulation",
                    "ae2lt:lightning_assembly" ->
                    recipeRequirementsFromMembers(recipe, aliases("inputs"));
            case "ae2lt:overload_processing" ->
                    recipeRequirementsFromMembers(recipe,
                            aliases("itemInputs"), aliases("fluidInput"));
            case "ae2lt:crystal_catalyzer" ->
                    crystalCatalyzerRequirements(recipe);
            // Lightning-strike recipes operate on a world structure, not an
            // AE processing inventory. They are deliberately not treated as a
            // valid encoded machine pattern.
            case "ae2lt:lightning_strike" -> List.of();
            default -> genericRecipeRequirements(recipe);
        };
    }

    private static List<RecipeRequirement> enderIoRecipeRequirements(Recipe<?> recipe) {
        List<RecipeRequirement> requirements = recipeRequirementsFromMembers(recipe,
                aliases("getInputs", "inputs"), aliases("getInput", "input"),
                aliases("getInputA", "inputA"), aliases("getInputB", "inputB"),
                aliases("getInputC", "inputC"),
                aliases("getFluidInput", "fluidInput"));
        if ("enderio:vat_fermenting".equals(recipeTypeId(recipe.getType()))) {
            requirements.addAll(genericRecipeRequirements(recipe));
        }
        return requirements;
    }

    private static List<RecipeRequirement> ifeuRecipeRequirements(Recipe<?> recipe) {
        List<RecipeRequirement> requirements =
                recipeRequirementsFromMembers(recipe, aliases("inputs", "getInputs"));
        requirements.removeIf(PatternScanner::isIfeuAirPlaceholder);
        return requirements;
    }

    private static boolean isIfeuAirPlaceholder(RecipeRequirement requirement) {
        if (requirement.ingredient() == null) {
            return requirement.identifiers().size() == 1
                    && requirement.identifiers().contains("ifeu:air");
        }
        ItemStack[] items = requirement.ingredient().getItems();
        if (items.length == 0) {
            return false;
        }
        for (ItemStack item : items) {
            if (item.isEmpty()
                    || !BuiltInRegistries.ITEM.getKey(item.getItem()).toString().equals("ifeu:air")) {
                return false;
            }
        }
        return true;
    }

    private static List<RecipeRequirement> draconicFusionRequirements(Recipe<?> recipe) {
        List<RecipeRequirement> requirements = genericRecipeRequirements(recipe);
        Object catalyst = firstMember(recipe, "getCatalyst", "catalyst");
        RecipeRequirement catalystRequirement = genericRequirement(catalyst, 1L);
        if (catalystRequirement != null) {
            requirements.add(catalystRequirement);
        }
        return requirements;
    }

    private static List<RecipeRequirement> crystalCatalyzerRequirements(Recipe<?> recipe) {
        Object catalyst = firstMember(recipe, "catalyst", "getCatalyst");
        if (catalyst instanceof java.util.Optional<?> optional) {
            catalyst = optional.orElse(null);
        }
        RecipeRequirement requirement = genericRequirement(catalyst,
                numericAmount(firstMember(recipe, "catalystCount", "getCatalystCount"), 1L));
        return requirement == null ? List.of() : List.of(requirement);
    }

    private static List<RecipeRequirement> bioFactoryRequirements(Recipe<?> recipe) {
        List<RecipeRequirement> requirements = recipeRequirementsFromMembers(recipe,
                aliases("getSpecimen", "specimen"), aliases("getInputs", "inputs"));
        Object fluid = firstMember(recipe, "processFluid", "getProcessFluid");
        if (fluid instanceof java.util.Optional<?> optional) {
            fluid = optional.orElse(null);
        }
        String identifier = resourceIdentifier(fluid);
        if (identifier != null) {
            requirements.add(new RecipeRequirement(null, Set.of(identifier),
                    numericAmount(firstMember(recipe,
                            "processFluidAmount", "getProcessFluidAmount"), 1L)));
        }
        return requirements;
    }

    private static List<RecipeRequirement> recipeRequirementsFromMembers(
            Recipe<?> recipe, String[]... memberAliases) {
        List<RecipeRequirement> requirements = new ArrayList<>();
        for (String[] aliases : memberAliases) {
            addRecipeRequirements(requirements, firstMember(recipe, aliases));
        }
        if (requirements.isEmpty()) {
            requirements.addAll(genericRecipeRequirements(recipe));
        }
        return requirements;
    }

    private static List<RecipeRequirement> genericRecipeRequirements(Recipe<?> recipe) {
        List<RecipeRequirement> requirements = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient != null && !ingredient.isEmpty()) {
                requirements.add(new RecipeRequirement(ingredient, Set.of(), 1));
            }
        }
        return requirements;
    }

    private static void addRecipeRequirements(List<RecipeRequirement> requirements, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof ItemStack stack && stack.isEmpty()) {
            return;
        }
        if (value instanceof net.neoforged.neoforge.fluids.FluidStack stack
                && stack.isEmpty()) {
            return;
        }
        if (value instanceof java.util.Optional<?> optional) {
            optional.ifPresent(entry -> addRecipeRequirements(requirements, entry));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object entry : map.values()) {
                addRecipeRequirements(requirements, entry);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object entry : iterable) {
                addRecipeRequirements(requirements, entry);
            }
            return;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                addRecipeRequirements(requirements, java.lang.reflect.Array.get(value, i));
            }
            return;
        }
        RecipeRequirement requirement = sizedIngredientFrom(value);
        if (requirement != null) {
            requirements.add(requirement);
        }
    }

    /**
     * Unwraps a SizedIngredient (NeoForge: ingredient() + count()), including
     * Mekanism's ItemStackIngredient wrapper and Ender IO's SizedIngredient.
     */
    private static RecipeRequirement sizedIngredientFrom(Object input) {
        if (input == null) {
            return null;
        }
        if (input instanceof ItemStack stack && stack.isEmpty()) {
            return null;
        }
        if (input instanceof net.neoforged.neoforge.fluids.FluidStack stack
                && stack.isEmpty()) {
            return null;
        }
        long amount = numericAmount(input, 1L);
        Object ingredient = invokeNoArg(input, "getIngredient", "ingredient");
        if (ingredient == null) {
            ingredient = input;
        } else {
            amount = numericAmount(ingredient, amount);
            Object nested = invokeNoArg(ingredient, "getIngredient", "ingredient");
            if (nested != null && nested != ingredient) {
                ingredient = nested;
            }
        }
        return genericRequirement(ingredient, amount);
    }

    private static RecipeRequirement genericRequirement(Object ingredient, long amount) {
        if (ingredient instanceof Ingredient itemIngredient) {
            return itemIngredient.isEmpty()
                    ? null
                    : new RecipeRequirement(itemIngredient, Set.of(), Math.max(1L, amount));
        }
        Set<String> identifiers = identifiersFrom(ingredient);
        return identifiers.isEmpty()
                ? null
                : new RecipeRequirement(null, identifiers, Math.max(1L, amount));
    }

    private static Object invokeNoArg(Object target, String... names) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name);
                return method.invoke(target);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            }
        }
        return null;
    }

    private static long numericAmount(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue() > 0 ? number.longValue() : fallback;
        }
        Object amount = invokeNoArg(value, "getAmount", "amount", "count", "getCount",
                "getAmountPerOperation");
        return amount instanceof Number number && number.longValue() > 0
                ? number.longValue()
                : fallback;
    }

    private static Set<String> identifiersFrom(Object value) {
        Set<String> identifiers = new HashSet<>();
        if (value == null || value instanceof Ingredient) {
            return identifiers;
        }
        String direct = resourceIdentifier(value);
        if (direct != null && !direct.isEmpty()) {
            identifiers.add(direct);
        }
        Object representations = invokeNoArg(value, "getRepresentations", "representations",
                "getChemicalStacks", "getStacks", "getFluids", "getItems");
        if (representations instanceof Iterable<?> iterable) {
            for (Object representation : iterable) {
                String identifier = resourceIdentifier(representation);
                if (identifier != null && !identifier.isEmpty()) {
                    identifiers.add(identifier);
                }
            }
        } else if (representations instanceof java.util.stream.Stream<?> stream) {
            stream.forEach(representation -> {
                String identifier = resourceIdentifier(representation);
                if (identifier != null && !identifier.isEmpty()) {
                    identifiers.add(identifier);
                }
            });
        } else if (representations != null && representations.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(representations);
            for (int i = 0; i < length; i++) {
                String identifier = resourceIdentifier(java.lang.reflect.Array.get(representations, i));
                if (identifier != null && !identifier.isEmpty()) {
                    identifiers.add(identifier);
                }
            }
        }
        return identifiers;
    }

    /**
     * Resolves registry IDs from optional integrations without linking against
     * Mekanism, Resourceful Bees, or another machine mod at compile time.
     */
    private static String resourceIdentifier(Object value) {
        return resourceIdentifier(value, new HashSet<>());
    }

    private static String resourceIdentifier(Object value, Set<Object> visited) {
        if (value == null || !visited.add(value)) {
            return null;
        }
        if (value instanceof net.neoforged.neoforge.fluids.FluidStack stack
                && stack.isEmpty()) {
            return null;
        }
        if (value instanceof ResourceLocation id) {
            return id.toString();
        }
        if (value instanceof ItemStack stack) {
            return stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        }
        if (value instanceof Item item) {
            return BuiltInRegistries.ITEM.getKey(item).toString();
        }
        if (value instanceof Block block) {
            return BuiltInRegistries.BLOCK.getKey(block).toString();
        }
        if (value instanceof BlockState state) {
            return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        }
        if (value instanceof net.minecraft.world.level.material.Fluid fluid) {
            return BuiltInRegistries.FLUID.getKey(fluid).toString();
        }
        if (value instanceof AEKey key) {
            return key.getId().toString();
        }
        if (value instanceof GenericStack stack) {
            return stack.what() == null ? null : stack.what().getId().toString();
        }
        if (value instanceof net.minecraft.core.Holder<?> holder) {
            var key = holder.unwrapKey();
            if (key.isPresent()) {
                return key.get().location().toString();
            }
            return resourceIdentifier(holder.value(), visited);
        }
        for (String methodName : new String[]{"getId", "getRegistryName", "registryName",
                "location", "getChemicalHolder", "getFluidHolder", "getChemical",
                "getFluid", "getKey", "getPrimaryKey", "what", "getWhat", "value", "getValue"}) {
            Object nested = invokeNoArg(value, methodName);
            if (nested != null && nested != value) {
                String identifier = resourceIdentifier(nested, visited);
                if (identifier != null) {
                    return identifier;
                }
            }
        }
        String text = value.toString();
        for (String token : text.split("[\\s\\[\\](),={}]+")) {
            int colon = token.indexOf(':');
            if (colon > 0 && colon < token.length() - 1
                    && token.substring(0, colon).matches("[a-z0-9_.-]+")
                    && token.substring(colon + 1).matches("[a-z0-9_./-]+")) {
                return token;
            }
        }
        return null;
    }

    private enum ProcessingMachineResult {
        MATCH,
        NO_TARGET,
        WRONG_MACHINE,
        NO_RECIPE,
        UNKNOWN
    }

    /**
     * Checks the processing pattern against the provider's actual push target.
     * A recipe found elsewhere in the registry is not enough: when the target
     * machine type is known, the recipe must belong to that type.
     */
    private static ProcessingMachineResult checkProcessingMachine(Object host, Level level,
                                                                  BlockPos pos, IPatternDetails details,
                                                                  ScanContext context) {
        if (host == null || pos == null) {
            // Patterns in ME storage are not assigned to a provider yet.
            return ProcessingMachineResult.UNKNOWN;
        }
        if (isAe2LtPackagedPatternProvider(host)) {
            return checkAe2LtPackagedProvider(host, level, pos, details, context);
        }

        MachineState machine = context.machineState(host, pos);
        Set<RecipeType<?>> types = machine.types();
        boolean hasTarget = machine.hasTarget();
        boolean craftingOnly = machine.craftingOnly();

        if (!hasTarget) {
            return ProcessingMachineResult.NO_TARGET;
        }
        if (machine.universalRecipeExecutor()) {
            return hasMatchingMachineRecipe(level, details, null, null, context)
                    ? ProcessingMachineResult.MATCH
                    : ProcessingMachineResult.NO_RECIPE;
        }
        // AE2 Crystal Science's growth chamber does not use Minecraft's
        // recipe registry. It grows a *_seed item in-place and turns it into
        // the corresponding purified crystal, so validate that transformation
        // directly before falling back to normal recipe-type matching.
        if (machine.crystalGrowthChamber() && matchesCrystalGrowthPattern(details)) {
            return ProcessingMachineResult.MATCH;
        }
        if (machine.crystalGrowthChamber() && types.isEmpty()) {
            return ProcessingMachineResult.NO_RECIPE;
        }
        // Molecular assemblers accept AE2 crafting plans, but processing
        // patterns are not crafting recipes and can never run in them.
        if (craftingOnly && types.isEmpty()) {
            return ProcessingMachineResult.WRONG_MACHINE;
        }
        if (machine.knownNonProcessingTarget() && !machine.unknownAddonTarget()
                && types.isEmpty()) {
            return ProcessingMachineResult.WRONG_MACHINE;
        }

        if (!types.isEmpty()) {
            boolean unresolvedMmrTarget = false;
            for (RecipeType<?> type : types) {
                if (MMR_RECIPE_TYPE.equals(recipeTypeId(type))) {
                    if (machine.machineIds().isEmpty()) {
                        unresolvedMmrTarget = true;
                        continue;
                    }
                    for (String machineId : machine.machineIds()) {
                        if (hasMatchingMachineRecipe(level, details, type, machineId, context)) {
                            return ProcessingMachineResult.MATCH;
                        }
                    }
                } else if (hasMatchingMachineRecipe(level, details, type, null, context)) {
                    return ProcessingMachineResult.MATCH;
                }
            }
            boolean recipeExists = hasMatchingMachineRecipe(
                    level, details, null, null, context);
            // The inputs/outputs form a valid machine recipe, but not for the
            // machine this provider is actually facing.
            if (recipeExists) {
                return unresolvedMmrTarget || machine.unknownAddonTarget()
                        ? ProcessingMachineResult.UNKNOWN
                        : ProcessingMachineResult.WRONG_MACHINE;
            }
            return hasNonItemIO(details)
                    ? ProcessingMachineResult.UNKNOWN
                    : ProcessingMachineResult.NO_RECIPE;
        }

        // An unknown execution target only makes the machine assignment
        // uncertain. It must not make an item-only processing pattern immune
        // to the global "does this recipe still exist?" validity check.
        return hasMatchingMachineRecipe(level, details, null, null, context)
                || hasNonItemIO(details)
                ? ProcessingMachineResult.UNKNOWN
                : ProcessingMachineResult.NO_RECIPE;
    }

    /**
     * Delegates packaged-provider validation to AE2LTPP's own adapter binding
     * API. This covers every adapter registered by the installed addon without
     * copying its per-mod recipe logic into Pattern Checker.
     */
    private static ProcessingMachineResult checkAe2LtPackagedProvider(
            Object host, Level level, BlockPos providerPos, IPatternDetails details,
            ScanContext context) {
        Map<DuplicateSignature, ProcessingMachineResult> hostCache =
                context.packagedProviderMatches.computeIfAbsent(host, ignored -> new HashMap<>());
        DuplicateSignature signature = duplicateSignature(details);
        ProcessingMachineResult cached = hostCache.get(signature);
        if (cached != null) {
            return cached;
        }
        ProcessingMachineResult result = checkAe2LtPackagedProviderUncached(
                host, level, providerPos, details, context);
        hostCache.put(signature, result);
        return result;
    }

    private static ProcessingMachineResult checkAe2LtPackagedProviderUncached(
            Object host, Level level, BlockPos providerPos, IPatternDetails details,
            ScanContext context) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return ProcessingMachineResult.UNKNOWN;
        }
        java.util.Optional<PackagedAdapterReflection> bridge =
                packagedAdapterReflection(host.getClass().getClassLoader());
        if (bridge.isEmpty()) {
            return ProcessingMachineResult.UNKNOWN;
        }

        ItemStack installedCore = ItemStack.EMPTY;
        Object core = firstMember(host, "getInstalledAdapterStack");
        if (core instanceof ItemStack stack) {
            installedCore = stack;
        }

        List<WirelessHelper.WirelessTarget> targets = new ArrayList<>();
        Object providerMode = firstMember(host, "getProviderMode");
        boolean wireless = providerMode != null
                && providerMode.toString().equalsIgnoreCase("WIRELESS");
        if (wireless) {
            targets.addAll(WirelessHelper.resolveConnectionLocations(serverLevel, host));
        } else {
            for (Direction direction : providerTargets(host)) {
                targets.add(new WirelessHelper.WirelessTarget(
                        serverLevel, providerPos.relative(direction)));
            }
        }
        if (targets.isEmpty()) {
            return ProcessingMachineResult.NO_TARGET;
        }

        boolean hasLoadedTarget = false;
        boolean hasUnloadedTarget = false;
        boolean recognizedAdapter = false;
        boolean unlockedAdapter = false;
        boolean reflectionFailed = false;
        PackagedAdapterReflection reflection = bridge.get();
        for (WirelessHelper.WirelessTarget target : targets) {
            ServerLevel targetLevel = target.level();
            BlockPos targetPos = target.pos();
            if (!targetLevel.isLoaded(targetPos)) {
                hasUnloadedTarget = true;
                continue;
            }
            if (!targetLevel.getBlockState(targetPos).isAir()) {
                hasLoadedTarget = true;
            }
            try {
                BlockEntity targetEntity = targetLevel.getBlockEntity(targetPos);
                Object adapter = reflection.findAdapter().invoke(
                        null, targetLevel, targetPos, targetEntity);
                if (adapter == null) {
                    continue;
                }
                recognizedAdapter = true;
                Object requiredId = reflection.requiredAdapterId().invoke(
                        adapter, targetLevel, targetPos);
                boolean unlocked = requiredId == null
                        || (!installedCore.isEmpty()
                        && Boolean.TRUE.equals(reflection.stackCovers().invoke(
                        null, installedCore, requiredId)));
                if (!unlocked) {
                    continue;
                }
                unlockedAdapter = true;
                Object binding = reflection.bind().invoke(
                        adapter, targetLevel, targetPos, details);
                boolean draconicFusion = adapter.getClass().getName().equals(
                        "com.moakiee.ae2lt.packaged.logic.multiblock.de.DraconicFusionCraftingAdapter");
                if (draconicFusion
                        && matchesDraconicFusionPattern(targetLevel, targetPos, details, context)) {
                    return ProcessingMachineResult.MATCH;
                }
                if (!draconicFusion && binding != null) {
                    return ProcessingMachineResult.MATCH;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                reflectionFailed = true;
            }
        }
        if (reflectionFailed && !recognizedAdapter) {
            return ProcessingMachineResult.UNKNOWN;
        }
        if (!hasLoadedTarget) {
            return hasUnloadedTarget
                    ? ProcessingMachineResult.UNKNOWN
                    : ProcessingMachineResult.NO_TARGET;
        }
        if (recognizedAdapter && !unlockedAdapter) {
            return ProcessingMachineResult.WRONG_MACHINE;
        }
        if (unlockedAdapter) {
            return ProcessingMachineResult.NO_RECIPE;
        }
        return ProcessingMachineResult.WRONG_MACHINE;
    }

    private static boolean matchesDraconicFusionPattern(
            ServerLevel level, BlockPos targetPos, IPatternDetails details,
            ScanContext context) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(
                level.getBlockState(targetPos).getBlock());
        if (!blockId.toString().equals("draconicevolution:crafting_core")) {
            return false;
        }
        ResourceLocation typeId = ResourceLocation.parse(
                "draconicevolution:fusion_crafting");
        RecipeType<?> type = BuiltInRegistries.RECIPE_TYPE.get(typeId);
        return type != null
                && typeId.equals(BuiltInRegistries.RECIPE_TYPE.getKey(type))
                && hasMatchingMachineRecipe(level, details, type, null, context);
    }

    private static boolean isAe2LtPackagedPatternProvider(Object owner) {
        return findNamedSuperclass(owner == null ? null : owner.getClass(),
                AE2LTPP_STABLE_PATTERN_PROVIDER) != null;
    }

    private static Class<?> findNamedSuperclass(Class<?> type, String className) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getName().equals(className)) {
                return current;
            }
        }
        return null;
    }

    private static java.util.Optional<PackagedAdapterReflection> packagedAdapterReflection(
            ClassLoader loader) {
        java.util.Optional<PackagedAdapterReflection> cached = PACKAGED_ADAPTER_REFLECTION;
        if (cached != null) {
            return cached;
        }
        synchronized (PatternScanner.class) {
            cached = PACKAGED_ADAPTER_REFLECTION;
            if (cached != null) {
                return cached;
            }
            try {
                Class<?> registry = Class.forName(AE2LTPP_ADAPTER_REGISTRY, false, loader);
                Class<?> adapter = Class.forName(AE2LTPP_ADAPTER_INTERFACE, false, loader);
                Class<?> adapterItem = Class.forName(AE2LTPP_ADAPTER_ITEM, false, loader);
                cached = java.util.Optional.of(new PackagedAdapterReflection(
                        registry.getMethod("find", ServerLevel.class, BlockPos.class, BlockEntity.class),
                        adapter.getMethod("requiredAdapterId", ServerLevel.class, BlockPos.class),
                        adapter.getMethod("bind", ServerLevel.class, BlockPos.class, IPatternDetails.class),
                        adapterItem.getMethod("stackCovers", ItemStack.class, ResourceLocation.class)));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                cached = java.util.Optional.empty();
            }
            PACKAGED_ADAPTER_REFLECTION = cached;
            return cached;
        }
    }

    private static MachineState buildMachineState(Object host, Level level, BlockPos pos) {
        Set<RecipeType<?>> types = new HashSet<>();
        boolean hasTarget = false;
        boolean craftingOnly = false;
        boolean acceptsPlans = false;
        boolean crystalGrowthChamber = false;
        boolean universalRecipeExecutor = false;
        boolean knownNonProcessingTarget = false;
        boolean unknownAddonTarget = false;
        Set<String> machineIds = new HashSet<>();
        Set<String> targetBlockIds = new HashSet<>();

        // Mek Energistics machines are both the pattern container and the
        // recipe executor. They have no adjacent provider target to inspect,
        // so their own block is the effective machine target.
        if ((isMekEnergisticsMachine(host) || isAe2LtMatrixPort(host)) && pos != null) {
            Block block = level.getBlockState(pos).getBlock();
            hasTarget = true;
            addTargetBlockId(block, targetBlockIds);
            addMachineType(block, types);
            craftingOnly |= isCraftingOnlyBlock(block);
            crystalGrowthChamber |= isCrystalGrowthChamber(block);
            universalRecipeExecutor |= isAe2LtUniversalRecipeExecutor(block);
            knownNonProcessingTarget |= isAe2LtNonProcessingTarget(block);
            unknownAddonTarget |= isUnknownAddonMachine(block);
        }

        if (host != null && pos != null) {
            for (Direction direction : providerTargets(host)) {
                BlockPos target = pos.relative(direction);
                Block block = level.getBlockState(target).getBlock();
                if (!level.getBlockState(target).isAir()) {
                    hasTarget = true;
                    addTargetBlockId(block, targetBlockIds);
                }
                addMachineType(block, types);
                craftingOnly |= isCraftingOnlyBlock(block);
                crystalGrowthChamber |= isCrystalGrowthChamber(block);
                universalRecipeExecutor |= isAe2LtUniversalRecipeExecutor(block);
                knownNonProcessingTarget |= isAe2LtNonProcessingTarget(block);
                unknownAddonTarget |= isUnknownAddonMachine(block);
                addMmrMachineId(level, target, machineIds);
                ICraftingMachine machine = ICraftingMachine.of(level, pos, direction);
                if (machine != null && machine.acceptsPlans()) {
                    acceptsPlans = true;
                }
            }
        }

        if (level instanceof ServerLevel serverLevel) {
            List<BlockPos> remoteTargets = WirelessHelper.resolveConnectionTargets(serverLevel, host);
            hasTarget |= !remoteTargets.isEmpty();
            for (BlockPos target : remoteTargets) {
                Block targetBlock = level.getBlockState(target).getBlock();
                if (!level.getBlockState(target).isAir()) {
                    addTargetBlockId(targetBlock, targetBlockIds);
                }
                addMachineType(targetBlock, types);
                craftingOnly |= isCraftingOnlyBlock(targetBlock);
                crystalGrowthChamber |= isCrystalGrowthChamber(targetBlock);
                universalRecipeExecutor |= isAe2LtUniversalRecipeExecutor(targetBlock);
                knownNonProcessingTarget |= isAe2LtNonProcessingTarget(targetBlock);
                unknownAddonTarget |= isUnknownAddonMachine(targetBlock);
                addMmrMachineId(level, target, machineIds);
                for (Direction direction : Direction.values()) {
                    BlockPos adjacentPos = target.relative(direction);
                    Block adjacent = level.getBlockState(adjacentPos).getBlock();
                    if (!level.getBlockState(adjacentPos).isAir()) {
                        addTargetBlockId(adjacent, targetBlockIds);
                    }
                    addMachineType(adjacent, types);
                    craftingOnly |= isCraftingOnlyBlock(adjacent);
                    crystalGrowthChamber |= isCrystalGrowthChamber(adjacent);
                    universalRecipeExecutor |= isAe2LtUniversalRecipeExecutor(adjacent);
                    knownNonProcessingTarget |= isAe2LtNonProcessingTarget(adjacent);
                    unknownAddonTarget |= isUnknownAddonMachine(adjacent);
                    addMmrMachineId(level, adjacentPos, machineIds);
                    ICraftingMachine machine = ICraftingMachine.of(level, target, direction);
                    if (machine != null && machine.acceptsPlans()) {
                        acceptsPlans = true;
                    }
                }
            }
        }
        return new MachineState(types, hasTarget, craftingOnly, acceptsPlans,
                crystalGrowthChamber, universalRecipeExecutor,
                knownNonProcessingTarget, unknownAddonTarget, Set.copyOf(machineIds),
                Set.copyOf(targetBlockIds));
    }

    private static void addTargetBlockId(Block block, Set<String> targetBlockIds) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (id != null) {
            targetBlockIds.add(id.toString());
        }
    }

    private static void addMmrMachineId(Level level, BlockPos target, Set<String> machineIds) {
        BlockEntity targetEntity = level.getBlockEntity(target);
        if (targetEntity == null) {
            return;
        }

        Object controller = targetEntity;
        Object controllerPos = invokeInterfaceNoArg(
                targetEntity, MMR_CONTROLLER_ACCESSIBLE, "getControllerPos");
        if (controllerPos instanceof BlockPos blockPos) {
            BlockEntity resolved = level.getBlockEntity(blockPos);
            if (resolved != null) {
                controller = resolved;
            }
        }

        Object machineId = invokeInterfaceNoArg(
                controller, MMR_MULTIBLOCK_CONTROLLER, "getId");
        String identifier = resourceIdentifier(machineId);
        if (identifier != null) {
            machineIds.add(identifier);
        }
    }

    private static boolean hasPhysicalTarget(PatternProviderLogicHost host, Level level, BlockPos pos) {
        for (Direction direction : host.getTargets()) {
            if (!level.getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }

    /**
     * True for machines that only execute crafting patterns.
     */
    private static boolean hasCraftingOnlyTarget(PatternProviderLogicHost host, Level level, BlockPos pos) {
        if (host == null || pos == null) {
            return false;
        }
        for (Direction direction : host.getTargets()) {
            if (isCraftingOnlyBlock(level.getBlockState(pos.relative(direction)).getBlock())) {
                return true;
            }
        }
        if (level instanceof ServerLevel serverLevel) {
            for (BlockPos target : WirelessHelper.resolveConnectionTargets(serverLevel, host)) {
                if (isCraftingOnlyBlock(level.getBlockState(target).getBlock())) {
                    return true;
                }
                for (Direction direction : Direction.values()) {
                    if (isCraftingOnlyBlock(level.getBlockState(target.relative(direction)).getBlock())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isCraftingOnlyBlock(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id.toString().equals("ae2:molecular_assembler")
                || id.toString().equals("extendedae:ex_molecular_assembler")
                || id.toString().equals("extendedae:assembler_matrix_crafter")
                || id.toString().equals("advanced_ae:quantum_crafter")
                || id.toString().equals("extendedae_plus:assembler_matrix_crafter_plus")
                || id.toString().equals("ae2lt:pigmee_molecular_assembler");
    }

    /**
     * The Matter Warping Matrix executes stored patterns internally instead of
     * forwarding them to one adjacent recipe machine.
     */
    private static boolean isAe2LtUniversalRecipeExecutor(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id.getNamespace().equals("ae2lt")
                && id.getPath().equals("matter_warping_matrix_port");
    }

    /**
     * AE2LT utility/control blocks with inventories are not processing-pattern
     * executors. Explicitly classifying them prevents a globally valid recipe
     * from being accepted merely because the target was previously unknown.
     */
    private static boolean isAe2LtNonProcessingTarget(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (!id.getNamespace().equals("ae2lt")) {
            return false;
        }
        String path = id.getPath();
        return switch (path) {
            case "tesla_coil",
                    "atmospheric_ionizer",
                    "overloaded_controller",
                    "wireless_overloaded_controller",
                    "advanced_wireless_overloaded_controller",
                    "overloaded_interface",
                    "overloaded_power_supply",
                    "wireless_receiver",
                    "overload_device_workbench",
                    "pigmee_mentalmath_unit",
                    "tianshu_supercomputer_controller",
                    "tianshu_supercomputer_port",
                    "closed_loop_pattern_storage",
                    "closed_loop_seed_storage",
                    "matter_warping_matrix_controller" -> true;
            default -> false;
        };
    }

    private static boolean isCrystalGrowthChamber(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id.getNamespace().equals("ae2cs")
                && id.getPath().equals("crystal_growth_chamber");
    }

    private static boolean isUnknownAddonMachine(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        String namespace = id.getNamespace();
        boolean optionalMachineMod = namespace.contains("ae2")
                || namespace.equals("extendedae")
                || namespace.equals("advancedae")
                || namespace.equals("advanced_ae")
                || namespace.equals("megacells")
                || namespace.equals("appflux")
                || namespace.equals("data_energistics")
                || namespace.equals("expatternprovider")
                || namespace.equals("justdirethings")
                || namespace.equals("justdynathings")
                || namespace.equals("jdte")
                || namespace.equals("industrialforegoing");
        if (!optionalMachineMod || namespace.equals("ae2")
                || isCraftingOnlyBlock(block) || isCrystalGrowthChamber(block)
                || isAe2LtUniversalRecipeExecutor(block)
                || isAe2LtNonProcessingTarget(block)) {
            return false;
        }
        return exactMachineRecipeTypeIds(id).isEmpty() && machineRecipeTypeId(id) == null;
    }

    private static boolean matchesCrystalGrowthPattern(IPatternDetails details) {
        AEItemKey input = null;
        int inputCount = 0;
        for (IPatternDetails.IInput patternInput : details.getInputs()) {
            GenericStack[] possible = patternInput.getPossibleInputs();
            if (possible == null) {
                continue;
            }
            for (GenericStack candidate : possible) {
                if (candidate != null && candidate.what() instanceof AEItemKey itemKey) {
                    input = itemKey;
                    inputCount++;
                }
            }
        }
        AEItemKey output = null;
        int outputCount = 0;
        for (GenericStack candidate : details.getOutputs()) {
            if (candidate != null && candidate.what() instanceof AEItemKey itemKey) {
                output = itemKey;
                outputCount++;
            }
        }
        if (inputCount != 1 || outputCount != 1 || input == null || output == null) {
            return false;
        }

        ResourceLocation inputId = BuiltInRegistries.ITEM.getKey(input.getItem());
        ResourceLocation outputId = BuiltInRegistries.ITEM.getKey(output.getItem());
        if (!inputId.getNamespace().equals("ae2cs")
                || !outputId.getNamespace().equals("ae2cs")) {
            return false;
        }
        String expectedOutput = crystalGrowthOutput(inputId.getPath());
        return expectedOutput != null && expectedOutput.equals(outputId.getPath());
    }

    private static String crystalGrowthOutput(String seedPath) {
        return switch (seedPath) {
            case "certus_quartz_seed" -> "purified_certus_quartz_crystal";
            case "fluix_crystal_seed" -> "purified_fluix_crystal";
            case "nether_quartz_seed" -> "purified_nether_quartz_crystal";
            case "ender_quartz_seed" -> "purified_ender_quartz";
            case "meteor_seed" -> "purified_meteor_crystal";
            case "resonating_seed" -> "purified_resonating_crystal";
            case "entro_crystal_seed" -> "purified_entro_crystal";
            case "redstone_crystal_seed" -> "purified_redstone_crystal";
            case "quantum_crystal_seed" -> "purified_quantum_crystal";
            case "rose_quartz_seed" -> "purified_rose_quartz";
            case "irradiated_seed" -> "purified_irradiated_crystal";
            case "ember_seed" -> "purified_ember_crystal";
            case "link_crystal_seed" -> "purified_link_crystal";
            case "overload_crystal_seed" -> "purified_overload_crystal";
            case "data_crystal_seed" -> "purified_data_crystal";
            case "energized_fluix_crystal_seed" -> "purified_energized_fluix_crystal";
            case "energized_certus_quartz_seed" -> "purified_energized_certus_quartz_crystal";
            default -> null;
        };
    }

    private static boolean hasFluidIO(IPatternDetails details) {
        for (IPatternDetails.IInput input : details.getInputs()) {
            for (GenericStack candidate : input.getPossibleInputs()) {
                if (candidate != null && candidate.what() instanceof AEFluidKey) {
                    return true;
                }
            }
        }
        for (GenericStack output : details.getOutputs()) {
            if (output != null && output.what() instanceof AEFluidKey) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNonItemIO(IPatternDetails details) {
        for (IPatternDetails.IInput input : details.getInputs()) {
            for (GenericStack candidate : input.getPossibleInputs()) {
                if (candidate != null && candidate.what() != null
                        && !(candidate.what() instanceof AEItemKey)) {
                    return true;
                }
            }
        }
        for (GenericStack output : details.getOutputs()) {
            if (output != null && output.what() != null
                    && !(output.what() instanceof AEItemKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recipe types of known vanilla machines, local and remote (wireless).
     */
    private static Set<RecipeType<?>> machineRecipeTypes(PatternProviderLogicHost host, Level level, BlockPos pos) {
        Set<RecipeType<?>> types = new HashSet<>();
        if (host != null && pos != null) {
            for (var direction : host.getTargets()) {
                addMachineType(level.getBlockState(pos.relative(direction)).getBlock(), types);
            }
        }
        if (level instanceof ServerLevel serverLevel) {
            for (BlockPos target : WirelessHelper.resolveConnectionTargets(serverLevel, host)) {
                for (var direction : Direction.values()) {
                    addMachineType(level.getBlockState(target.relative(direction)).getBlock(), types);
                }
            }
        }
        return types;
    }

    private static void addMachineType(Block block, Set<RecipeType<?>> types) {
        if (block instanceof FurnaceBlock) {
            types.add(RecipeType.SMELTING);
        } else if (block instanceof BlastFurnaceBlock) {
            types.add(RecipeType.BLASTING);
        } else if (block instanceof SmokerBlock) {
            types.add(RecipeType.SMOKING);
        } else if (block instanceof CampfireBlock) {
            types.add(RecipeType.CAMPFIRE_COOKING);
        } else if (block instanceof StonecutterBlock) {
            types.add(RecipeType.STONECUTTING);
        } else if (block instanceof SmithingTableBlock) {
            types.add(RecipeType.SMITHING);
        } else {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            List<String> exactTypes = exactMachineRecipeTypeIds(id);
            if (!exactTypes.isEmpty()) {
                for (String exactType : exactTypes) {
                    addRecipeType(types, exactType);
                }
                return;
            }
            String recipeTypeId = machineRecipeTypeId(id);
            if (recipeTypeId != null) {
                addRecipeType(types, recipeTypeId);
            }
        }
    }

    private static void addRecipeType(Set<RecipeType<?>> types, String recipeTypeId) {
        ResourceLocation id = ResourceLocation.parse(recipeTypeId);
        RecipeType<?> type = BuiltInRegistries.RECIPE_TYPE.get(id);
        if (type != null && id.equals(BuiltInRegistries.RECIPE_TYPE.getKey(type))) {
            types.add(type);
        }
    }

    /**
     * Exact block-to-recipe mappings verified against the JARs in the target
     * NAST hard d0.9.2 instance. Optional mods remain reflection-only: registry
     * IDs are resolved at runtime and no binary dependency is introduced.
     */
    private static List<String> exactMachineRecipeTypeIds(ResourceLocation blockId) {
        String id = blockId.toString();
        if (isMekanismSmeltingMachine(blockId)) {
            // Mekanism's smelters accept both native Mekanism smelting recipes
            // and ordinary furnace recipes. This also covers the verified
            // Mekanism Extras and Mek Energistics factory variants.
            return List.of("mekanism:smelting", "minecraft:smelting");
        }
        if (blockId.getNamespace().equals("mekanism")) {
            String type = exactMekanismRecipeType(blockId.getPath());
            return type == null ? List.of() : List.of(type);
        }
        if (blockId.getNamespace().equals("modular_machinery_reborn")
                && (blockId.getPath().equals("controller")
                || blockId.getPath().startsWith("inputbus_")
                || blockId.getPath().startsWith("outputbus_"))) {
            return List.of(MMR_RECIPE_TYPE);
        }
        return switch (id) {
            case "ae2:charger", "extendedae:ex_charger" ->
                    List.of("ae2:charger");
            case "ae2:inscriber", "extendedae:ex_inscriber" ->
                    List.of("ae2:inscriber");
            case "advanced_ae:reaction_chamber" ->
                    List.of("advanced_ae:reaction");
            case "extendedae:circuit_cutter" ->
                    List.of("extendedae:circuit_cutter");
            case "extendedae:crystal_assembler" ->
                    List.of("extendedae:crystal_assembler");
            case "extendedae:crystal_fixer" ->
                    List.of("extendedae:crystal_fixer");
            case "data_energistics:data_charger", "data_energistics:extended_data_charger" ->
                    List.of("data_energistics:data_charger", "ae2:charger");
            case "data_energistics:data_reassembler" ->
                    List.of("data_energistics:data_reassembler");
            case "ae2lt:overload_processing_factory" ->
                    List.of("ae2lt:overload_processing");
            case "ae2lt:lightning_assembly_chamber" ->
                    List.of("ae2lt:lightning_assembly");
            case "ae2lt:lightning_simulation_room" ->
                    List.of("ae2lt:lightning_simulation");
            case "ae2lt:lightning_collector" ->
                    List.of("ae2lt:lightning_transform");
            case "ae2lt:crystal_catalyzer" ->
                    List.of("ae2lt:crystal_catalyzer");
            case "ae2lt:firmament_conversion_core" ->
                    List.of("ae2lt:firmament_conversion");
            case "neoecoae:integrated_working_station" ->
                    List.of("neoecoae:integrated_working_station");
            case "neoecoae:computation_cooling_controller_l4",
                    "neoecoae:computation_cooling_controller_l6",
                    "neoecoae:computation_cooling_controller_l9" ->
                    List.of("neoecoae:cooling");
            case "ae2cs:circuit_etcher" ->
                    List.of("ae2cs:circuit_etcher_recipe");
            case "ae2cs:crystal_aggregator" ->
                    List.of("ae2cs:crystal_aggregator_recipe");
            case "ae2cs:crystal_pulverizer" ->
                    List.of("ae2cs:crystal_pulverizer_recipe");
            case "industrialforegoing:dissolution_chamber" ->
                    List.of("industrialforegoing:dissolution_chamber");
            case "ifeu:precision_crafting_table" ->
                    List.of("ifeu:precision_shaped", "ifeu:precision_shapeless");
            case "draconicevolution:crafting_core" ->
                    List.of("draconicevolution:fusion_crafting");
            case "industrialforegoing:fluid_extractor" ->
                    List.of("industrialforegoing:fluid_extractor");
            case "industrialforegoing:ore_laser_base" ->
                    List.of("industrialforegoing:laser_drill_ore");
            case "industrialforegoing:fluid_laser_base" ->
                    List.of("industrialforegoing:laser_drill_fluid");
            case "industrialforegoing:material_stonework_factory" ->
                    List.of("industrialforegoing:stonework_generate",
                            "industrialforegoing:crusher", "minecraft:smelting");
            case "industrialforegoing:resourceful_furnace" ->
                    List.of("minecraft:smelting");
            case "powah:energizing_orb" ->
                    List.of("powah:energizing");
            case "justdynathings:paradox_mixer" ->
                    List.of("justdynathings:paradox_mixer");
            case "justdynathings:reforger" ->
                    List.of("justdynathings:reforger_conversion/block_to_block",
                            "justdynathings:reforger_conversion/block_to_tag",
                            "justdynathings:reforger_conversion/tag_to_block");
            case "jdte:advanced_infusion_machine", "jdte:extended_infusion_machine" ->
                    List.of("jdte:infusion");
            case "jdte:bio_factory" ->
                    List.of("jdte:bio_factory");
            case "jdte:greenhouse", "jdte:large_greenhouse" ->
                    List.of("jdte:greenhouse");
            case "jdte:life_synthesis_vat" ->
                    List.of("jdte:life_synthesis");
            case "productivebees:centrifuge", "productivebees:powered_centrifuge",
                    "productivebees:heated_centrifuge" ->
                    List.of("productivebees:centrifuge");
            case "productivebees:bottler" ->
                    List.of("productivebees:bottler");
            default -> List.of();
        };
    }

    private static boolean isMekanismSmeltingMachine(ResourceLocation blockId) {
        String namespace = blockId.getNamespace();
        if (!namespace.equals("mekanism")
                && !namespace.equals("mekanism_extras")
                && !namespace.equals("mekenergistics")) {
            return false;
        }
        String path = blockId.getPath();
        return path.equals("energized_smelter")
                || path.equals("me_energized_smelter")
                || path.endsWith("_smelting_factory");
    }

    private static String exactMekanismRecipeType(String path) {
        String factoryProcess = mekanismFactoryProcess(path);
        if (factoryProcess != null) {
            return factoryProcess.equals("infusing")
                    ? "mekanism:metallurgic_infusing"
                    : "mekanism:" + factoryProcess;
        }
        return switch (path) {
            case "crusher" -> "mekanism:crushing";
            case "enrichment_chamber" -> "mekanism:enriching";
            case "energized_smelter" -> "mekanism:smelting";
            case "combiner" -> "mekanism:combining";
            case "purification_chamber" -> "mekanism:purifying";
            case "chemical_injection_chamber" -> "mekanism:injecting";
            case "metallurgic_infuser" -> "mekanism:metallurgic_infusing";
            case "precision_sawmill" -> "mekanism:sawing";
            case "osmium_compressor" -> "mekanism:compressing";
            case "chemical_infuser" -> "mekanism:chemical_infusing";
            case "chemical_oxidizer" -> "mekanism:oxidizing";
            case "chemical_dissolution_chamber" -> "mekanism:dissolution";
            case "chemical_crystallizer" -> "mekanism:crystallizing";
            case "electrolytic_separator" -> "mekanism:separating";
            case "isotopic_centrifuge" -> "mekanism:centrifuging";
            case "pressurized_reaction_chamber" -> "mekanism:reaction";
            case "solar_neutron_activator" -> "mekanism:activating";
            case "chemical_washer" -> "mekanism:washing";
            case "antiprotonic_nucleosynthesizer" -> "mekanism:nucleosynthesizing";
            case "pigment_extractor" -> "mekanism:pigment_extracting";
            case "pigment_mixer" -> "mekanism:pigment_mixing";
            case "painting_machine" -> "mekanism:painting";
            case "rotary_condensentrator" -> "mekanism:rotary";
            case "thermal_evaporation_controller", "thermal_evaporation_valve" ->
                    "mekanism:evaporating";
            default -> null;
        };
    }

    private static String mekanismFactoryProcess(String path) {
        for (String tier : new String[]{"basic", "advanced", "elite", "ultimate"}) {
            String prefix = tier + "_";
            String suffix = "_factory";
            if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
                continue;
            }
            String process = path.substring(prefix.length(), path.length() - suffix.length());
            return switch (process) {
                case "combining", "compressing", "crushing", "enriching", "infusing",
                        "injecting", "purifying", "sawing", "smelting" -> process;
                default -> null;
            };
        }
        return null;
    }

    private static Object firstMember(Object target, String... names) {
        for (String name : names) {
            Object value = readMember(target, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Object readMember(Object target, String name) {
        if (target == null) {
            return null;
        }
        var accessors = MEMBER_ACCESSORS.computeIfAbsent(
                target.getClass(), ignored -> new java.util.concurrent.ConcurrentHashMap<>());
        var accessor = accessors.computeIfAbsent(name, ignored -> findMemberAccessor(target.getClass(), name));
        if (accessor.isEmpty()) {
            return null;
        }
        try {
            return accessor.get().get(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static java.util.Optional<MemberAccessor> findMemberAccessor(Class<?> type, String name) {
        try {
            Method method = type.getMethod(name);
            return java.util.Optional.of(method::invoke);
        } catch (NoSuchMethodException | RuntimeException | LinkageError ignored) {
        }
        try {
            java.lang.reflect.Field field = type.getField(name);
            return java.util.Optional.of(field::get);
        } catch (NoSuchFieldException | RuntimeException | LinkageError ignored) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Conservative legacy fallback for compatible addons outside the verified
     * target-pack mappings above. Returns null for unknown blocks.
     */
    private static String machineRecipeTypeId(ResourceLocation blockId) {
        String path = blockId.getPath();
        String normalized = path
                .replaceFirst("^(me_)?(basic|advanced|elite|ultimate|absolute|cosmic|infinite|creative|dense|supernova|compressed|evolved|compact)_", "")
                .replace("_factory", "")
                .replace("_machine", "");

        // Resourceful Bees / Productive Bees style centrifuges expose
        // multi-output recipes under their own namespace rather than using a
        // vanilla recipe type.
        if (normalized.contains("centrifuge")) {
            String namespace = blockId.getNamespace();
            if (namespace.equals("resourcefulbees")
                    || namespace.equals("productivebees")
                    || namespace.equals("beesourceful")) {
                return namespace + ":centrifuge";
            }
        }

        // Ender IO name compatibility is restricted to its own addons.
        if (isEnderIoRecipeNamespace(blockId.getNamespace())) {
            if (normalized.endsWith("alloy_smelter") || normalized.endsWith("alloy_smelting")
                    || normalized.equals("alloy_smelter") || normalized.equals("alloying")) {
                return "enderio:alloy_smelting";
            }
            if (normalized.endsWith("sag_mill") || normalized.equals("sag_milling")
                    || normalized.endsWith("sag_milling")) {
                return "enderio:sag_milling";
            }
            if (normalized.endsWith("slicer") || normalized.equals("slicing")) {
                return "enderio:slicing";
            }
            if (normalized.endsWith("soul_binder") || normalized.equals("soul_binding")) {
                return "enderio:soul_binding";
            }
            if (normalized.endsWith("painter") || normalized.equals("painting")) {
                return "enderio:painting";
            }
            if (normalized.endsWith("enchanter") || normalized.equals("enchanting")) {
                return "enderio:enchanting";
            }
            if (normalized.endsWith("vat") || normalized.equals("vat_fermenting")
                    || normalized.endsWith("fermenting")) {
                return "enderio:vat_fermenting";
            }
        }

        // AE2 Crystal Science (ae2cs) machines.
        if (blockId.getNamespace().equals("ae2cs")) {
            if (normalized.contains("circuit_etcher") || normalized.equals("circuit_etcher")) {
                return "ae2cs:circuit_etcher_recipe";
            }
            if (normalized.contains("crystal_aggregator") || normalized.equals("crystal_aggregator")) {
                return "ae2cs:crystal_aggregator_recipe";
            }
            if (normalized.contains("crystal_pulverizer") || normalized.contains("grindstone")
                    || normalized.equals("crystal_pulverizer") || normalized.equals("quartz_grindstone")) {
                return "ae2cs:crystal_pulverizer_recipe";
            }
        }

        // Mekanism machines (base mod + verified addons). Do not apply these
        // generic name checks to unrelated mods: names such as JDTE's
        // bio_crusher and Industrial Foregoing's washing_factory are not
        // Mekanism recipe machines.
        if (!isMekanismRecipeNamespace(blockId.getNamespace())) {
            return null;
        }
        if (normalized.contains("enriching") || normalized.contains("enrichment")) {
            return "mekanism:enriching";
        }
        if (normalized.contains("crushing") || normalized.contains("crusher")) {
            return "mekanism:crushing";
        }
        if (normalized.contains("combining") || normalized.contains("combiner")) {
            return "mekanism:combining";
        }
        if (normalized.contains("purifying") || normalized.contains("purification")) {
            return "mekanism:purifying";
        }
        if (normalized.contains("smelting") || normalized.contains("energized_smelter")) {
            return "mekanism:smelting";
        }
        if (normalized.contains("chemical_infusing") || normalized.contains("chemical_infuser")) {
            return "mekanism:chemical_infusing";
        }
        if (normalized.contains("metallurgic_infusing") || normalized.equals("metallurgic_infuser")
                || normalized.equals("metallurgic_infusion")) {
            return "mekanism:metallurgic_infusing";
        }
        if (normalized.contains("sawing") || normalized.contains("sawmill")) {
            return "mekanism:sawing";
        }
        if (normalized.contains("painting")) {
            return "mekanism:painting";
        }
        if (normalized.contains("pigment_extract")) {
            return "mekanism:pigment_extracting";
        }
        if (normalized.contains("pigment_mix")) {
            return "mekanism:pigment_mixing";
        }
        if (normalized.contains("injecting") || normalized.contains("injection")) {
            return "mekanism:injecting";
        }
        if (normalized.contains("oxidizing") || normalized.contains("oxidizer")) {
            return "mekanism:oxidizing";
        }
        if (normalized.contains("dissolution") || normalized.contains("dissolving")) {
            return "mekanism:dissolution";
        }
        if (normalized.contains("crystallizing") || normalized.contains("crystallizer")) {
            return "mekanism:crystallizing";
        }
        if (normalized.contains("separating") || normalized.contains("separator")) {
            return "mekanism:separating";
        }
        if (normalized.contains("centrifuging") || normalized.contains("centrifuge")) {
            return "mekanism:centrifuging";
        }
        if (normalized.contains("reacting") || normalized.contains("reaction")) {
            return "mekanism:reaction";
        }
        if (normalized.contains("activating") || normalized.contains("activator")) {
            return "mekanism:activating";
        }
        if (normalized.contains("compressing") || normalized.contains("compressor")) {
            return "mekanism:compressing";
        }
        if (normalized.contains("washing") || normalized.contains("washer")) {
            return "mekanism:washing";
        }
        if (normalized.contains("nucleosynthesizing") || normalized.contains("nucleosynthesizer")) {
            return "mekanism:nucleosynthesizing";
        }
        if (normalized.contains("evaporating") || normalized.contains("evaporation")) {
            return "mekanism:evaporating";
        }
        if (normalized.contains("rotary") || normalized.contains("condensentrator")) {
            return "mekanism:rotary";
        }
        return null;
    }

    private static boolean isMekanismRecipeNamespace(String namespace) {
        return namespace.equals("mekanism")
                || namespace.equals("mekanism_extras")
                || namespace.equals("mekenergistics")
                || namespace.equals("mekmm")
                || namespace.equals("compactmekanismmachines")
                || namespace.equals("compactmekanismmachinesplus")
                || namespace.equals("mekanismsun")
                || namespace.equals("custommachinerymekanism");
    }

    private static boolean isEnderIoRecipeNamespace(String namespace) {
        return namespace.equals("enderio")
                || namespace.equals("enderio_evolution")
                || namespace.equals("enderio_conduits")
                || namespace.equals("enderio_machines");
    }

    /**
     * AE2's canonical signal: a machine that accepts pushed patterns.
     */
    private static boolean hasCraftingMachine(PatternProviderLogicHost host, Level level, BlockPos pos) {
        if (host != null && pos != null) {
            for (var direction : host.getTargets()) {
                ICraftingMachine machine = ICraftingMachine.of(level, pos, direction);
                if (machine != null && machine.acceptsPlans()) {
                    return true;
                }
            }
        }
        if (level instanceof ServerLevel serverLevel) {
            for (BlockPos target : WirelessHelper.resolveConnectionTargets(serverLevel, host)) {
                for (var direction : Direction.values()) {
                    ICraftingMachine machine = ICraftingMachine.of(level, target, direction);
                    if (machine != null && machine.acceptsPlans()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns the issues to show, hiding input-supply issues unless enabled.
     */
    public static List<PatternIssue> filterIssues(List<PatternIssue> issues, boolean showInput,
                                                  boolean showDuplicates) {
        if (showInput && showDuplicates) {
            return issues;
        }
        return issues.stream()
                .filter(issue -> showInput || issue.category() != PatternIssue.Category.INPUT)
                .filter(issue -> showDuplicates || issue.category() != PatternIssue.Category.DUPLICATE)
                .toList();
    }

    /**
     * Visible error/warning counts per scan result.
     */
    public static int[] visibleCounts(ScanResult result, boolean showInput, boolean showDuplicates) {
        int errors = 0;
        int warnings = 0;
        for (ScannedPattern pattern : result.patterns()) {
            for (PatternIssue issue : filterIssues(pattern.issues(), showInput, showDuplicates)) {
                if (issue.type() == PatternIssue.Type.ERROR) {
                    errors++;
                } else {
                    warnings++;
                }
            }
        }
        return new int[]{errors, warnings};
    }

    private static MutableComponent verdictLine(String key, Component patternName, String location, String detail) {
        MutableComponent line = Component.translatable(key)
                .append(Component.literal(" - "))
                .append(patternName)
                .append(Component.literal(" @ "))
                .append(Component.literal(location));
        if (detail != null && !detail.isEmpty()) {
            line.append(Component.literal(" (")).append(Component.literal(detail)).append(Component.literal(")"));
        }
        return line;
    }

    private static MutableComponent message(String key, Component patternName, String location, String detail) {
        MutableComponent message = Component.translatable(key)
                .append(Component.literal(" - "))
                .append(patternName)
                .append(Component.literal(" @ "))
                .append(Component.literal(location));
        if (detail != null && !detail.isEmpty()) {
            message.append(Component.literal(" (")).append(Component.literal(detail)).append(Component.literal(")"));
        }
        return message;
    }
}
