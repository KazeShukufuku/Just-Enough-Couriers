package com.justenoughcouriers.compat.jei;

import com.kreidev.cmpackagecouriers.stock_ticker.PortableStockTickerMenu;
import com.kreidev.cmpackagecouriers.stock_ticker.PortableStockTickerScreen;
import com.simibubi.create.content.logistics.stockTicker.CraftableBigItemStack;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedRecipe;
import com.simibubi.create.foundation.fluid.FluidIngredient;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import ru.zznty.create_factory_abstractions.api.generic.stack.GenericIngredient;
import ru.zznty.create_factory_abstractions.api.generic.stack.GenericStack;
import ru.zznty.create_factory_abstractions.compat.jei.IngredientTransfer;
import ru.zznty.create_factory_abstractions.compat.jei.TransferOperationsResult;
import ru.zznty.create_factory_abstractions.generic.key.item.ItemKey;
import ru.zznty.create_factory_abstractions.generic.support.CraftableGenericStack;
import ru.zznty.create_factory_abstractions.generic.support.GenericInventorySummary;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class PortableStockTickerTransferHandler implements IUniversalRecipeTransferHandler<PortableStockTickerMenu> {
    private static final int MAX_INGREDIENT_SLOTS = 9;
    private static final String FLUIDLOGISTICS_COMPRESSED_TANK_ID = "fluidlogistics:compressed_storage_tank";

    private final IJeiHelpers jeiHelpers;
    private final IRecipeTransferHandlerHelper transferHelper;

    public PortableStockTickerTransferHandler(IJeiHelpers jeiHelpers, IRecipeTransferHandlerHelper transferHelper) {
        this.jeiHelpers = jeiHelpers;
        this.transferHelper = transferHelper;
    }

    @Override
    public Class<? extends PortableStockTickerMenu> getContainerClass() {
        return PortableStockTickerMenu.class;
    }

    @Override
    public Optional<MenuType<PortableStockTickerMenu>> getMenuType() {
        return Optional.empty();
    }

    @Override
    public IRecipeTransferError transferRecipe(@Nonnull PortableStockTickerMenu menu, @Nonnull Object recipeObject, @Nonnull IRecipeSlotsView recipeSlots, @Nonnull Player player, boolean maxTransfer, boolean doTransfer) {
        if (!(recipeObject instanceof Recipe<?> recipe)) {
            return null;
        }
        if (!player.level().isClientSide) {
            return null;
        }
        return transferRecipeOnClient(menu, recipe, recipeSlots, player, maxTransfer, doTransfer);
    }

    private IRecipeTransferError transferRecipeOnClient(PortableStockTickerMenu menu, Recipe<?> recipe, IRecipeSlotsView recipeSlotsView, Player player, boolean maxTransfer, boolean doTransfer) {
        if (!(menu.screenReference instanceof PortableStockTickerScreen screen)) {
            return transferHelper.createInternalError();
        }
        if (recipe.getIngredients().size() > MAX_INGREDIENT_SLOTS) {
            return transferHelper.createInternalError();
        }
        if (screen.itemsToOrder().size() >= MAX_INGREDIENT_SLOTS) {
            return transferHelper.createUserErrorWithTooltip(Objects.requireNonNull(Component.translatable("create.gui.stock_keeper.slots_full")));
        }

        for (CraftableGenericStack existing : screen.recipesToOrder()) {
            if (existing.asStack().recipe == recipe) {
                return transferHelper.createUserErrorWithTooltip(Objects.requireNonNull(Component.translatable("create.gui.stock_keeper.already_ordering_recipe")));
            }
        }

        GenericInventorySummary stockSnapshot = screen.stockSnapshot();
        if (stockSnapshot == null) {
            return transferHelper.createInternalError();
        }

        Container recipeContainer = new RecipeWrapper(new ItemStackHandler(MAX_INGREDIENT_SLOTS));
        List<Slot> recipeSlots = new ArrayList<>();
        for (int i = 0; i < recipeContainer.getContainerSize(); i++) {
            recipeSlots.add(new Slot(recipeContainer, i, 0, 0));
        }

        List<GenericStack> stockStacks = stockSnapshot.get();
        TransferOperationsResult transferOperations = IngredientTransfer.getRecipeTransferOperations(
                jeiHelpers.getIngredientManager(),
                stockStacks,
                recipeSlotsView.getSlotViews(RecipeIngredientRole.INPUT),
                recipeSlots
        );

        List<IRecipeSlotView> unresolvedMissing = resolveFluidlogisticsMissing(
                transferOperations.missingItems(),
                stockStacks
        );

        if (!unresolvedMissing.isEmpty()) {
            return transferHelper.createUserErrorForMissingSlots(
                    Objects.requireNonNull(Component.translatable("create.gui.stock_keeper.not_in_stock")),
                    unresolvedMissing
            );
        }

        if (!doTransfer) {
            return null;
        }

        ItemStack recipeResult = recipe.getResultItem(requireRegistryAccess(player.level()));
        if (recipeResult.isEmpty()) {
            return transferHelper.createUserErrorWithTooltip(Objects.requireNonNull(Component.translatable("create.gui.stock_keeper.recipe_result_empty")));
        }

        List<GenericIngredient> transferIngredients = buildTransferIngredients(recipe, recipeSlotsView);
        CraftableGenericStack craftable = new PortableCompatibleCraftableStack(recipeResult.copy(), recipe, transferIngredients);
        screen.recipesToOrder().add(craftable);
        if (screen.searchBox != null) {
            screen.searchBox.setValue("");
        }
        screen.refreshSearchNextTick = true;

        int requestAmount = maxTransfer ? recipeResult.getMaxStackSize() : 1;
        screen.requestCraftable(craftable, requestAmount);
        return null;
    }

    private List<IRecipeSlotView> resolveFluidlogisticsMissing(List<IRecipeSlotView> missingSlots, List<GenericStack> stockStacks) {
        if (missingSlots.isEmpty()) {
            return missingSlots;
        }

        List<FluidStockEntry> fluidStock = extractFluidlogisticsStock(stockStacks);
        if (fluidStock.isEmpty()) {
            return missingSlots;
        }

        List<IRecipeSlotView> unresolved = new ArrayList<>();
        for (IRecipeSlotView missingSlot : missingSlots) {
            List<FluidStack> candidates = extractFluidCandidates(missingSlot);
            if (candidates.isEmpty()) {
                unresolved.add(missingSlot);
                continue;
            }

            if (!consumeMatchingFluid(candidates, fluidStock)) {
                unresolved.add(missingSlot);
            }
        }
        return unresolved;
    }

    private static List<FluidStockEntry> extractFluidlogisticsStock(List<GenericStack> stockStacks) {
        List<FluidStockEntry> entries = new ArrayList<>();
        for (GenericStack genericStack : stockStacks) {
            if (!(genericStack.key() instanceof ItemKey itemKey)) {
                continue;
            }

            ItemStack stack = itemKey.stack();
            if (!isVirtualCompressedTank(stack)) {
                continue;
            }

            FluidStack fluid = readFluidFromCompressedTank(stack);
            if (fluid.isEmpty() || genericStack.amount() <= 0) {
                continue;
            }

            mergeFluidEntry(entries, fluid, genericStack.amount());
        }
        return entries;
    }

    private static List<FluidStack> extractFluidCandidates(IRecipeSlotView slotView) {
        return slotView.getAllIngredients()
                .map(typedIngredient -> typedIngredient.getIngredient())
                .filter(ingredient -> ingredient instanceof FluidStack)
                .map(ingredient -> (FluidStack) ingredient)
                .filter(fluidStack -> !fluidStack.isEmpty())
                .toList();
    }

    private static boolean consumeMatchingFluid(List<FluidStack> candidates, List<FluidStockEntry> stockEntries) {
        FluidStockEntry bestEntry = null;
        int bestRequired = 0;

        for (FluidStack candidate : candidates) {
            int required = Math.max(1, candidate.getAmount());
            for (FluidStockEntry entry : stockEntries) {
                if (!entry.matches(candidate) || entry.amount < required) {
                    continue;
                }

                if (bestEntry == null || entry.amount > bestEntry.amount) {
                    bestEntry = entry;
                    bestRequired = required;
                }
            }
        }

        if (bestEntry == null) {
            return false;
        }

        bestEntry.amount -= bestRequired;
        return true;
    }

    private static void mergeFluidEntry(List<FluidStockEntry> entries, FluidStack fluid, int amount) {
        for (FluidStockEntry existing : entries) {
            if (existing.matches(fluid)) {
                existing.amount += amount;
                return;
            }
        }
        entries.add(new FluidStockEntry(fluid.copy(), amount));
    }

    private static boolean isVirtualCompressedTank(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        String itemId = String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()));
        if (!FLUIDLOGISTICS_COMPRESSED_TANK_ID.equals(itemId)) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains("Virtual") && tag.getBoolean("Virtual") && tag.contains("Fluid");
    }

    private static FluidStack readFluidFromCompressedTank(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("Fluid")) {
            return FluidStack.EMPTY;
        }
        return FluidStack.loadFluidStackFromNBT(tag.getCompound("Fluid"));
    }

    private static class FluidStockEntry {
        private final FluidStack fluid;
        private int amount;

        private FluidStockEntry(FluidStack fluid, int amount) {
            this.fluid = fluid;
            this.amount = amount;
        }

        private boolean matches(FluidStack other) {
            return !other.isEmpty() && fluid.isFluidEqual(other);
        }
    }

    private List<GenericIngredient> buildTransferIngredients(Recipe<?> recipe, IRecipeSlotsView recipeSlotsView) {
        if (recipe instanceof SequencedAssemblyRecipe sequencedAssemblyRecipe) {
            return buildSequencedAssemblyTransferIngredients(sequencedAssemblyRecipe);
        }

        List<GenericIngredient> ingredients = new ArrayList<>();
        for (IRecipeSlotView slotView : recipeSlotsView.getSlotViews(RecipeIngredientRole.INPUT)) {
            if (slotView.isEmpty()) {
                continue;
            }

            List<GenericStack> options = slotView.getAllIngredients()
                    .map(this::convertToTransferStack)
                    .flatMap(Optional::stream)
                    .filter(stack -> !stack.isEmpty())
                    .toList();
            if (options.isEmpty()) {
                continue;
            }

            int requiredAmount = Math.max(1, options.stream().mapToInt(GenericStack::amount).max().orElse(1));
            ingredients.add(new TransferSlotIngredient(options, requiredAmount));
        }

        if (!ingredients.isEmpty()) {
            return ingredients;
        }
        return GenericIngredient.ofRecipe(recipe);
    }

    private List<GenericIngredient> buildSequencedAssemblyTransferIngredients(SequencedAssemblyRecipe recipe) {
        List<GenericIngredient> ingredients = new ArrayList<>();
        int loops = Math.max(1, recipe.getLoops());

        // Base input is consumed once per craft; repeated loop costs are modeled from sequence steps below.
        appendItemIngredient(ingredients, recipe.getIngredient(), 1);

        for (SequencedRecipe<?> sequencedStep : recipe.getSequence()) {
            ProcessingRecipe<?> stepRecipe = sequencedStep.getRecipe();
            List<net.minecraft.world.item.crafting.Ingredient> stepIngredients = stepRecipe.getIngredients();
            for (int i = 1; i < stepIngredients.size(); i++) {
                appendItemIngredient(ingredients, stepIngredients.get(i), loops);
            }

            for (FluidIngredient fluidIngredient : stepRecipe.getFluidIngredients()) {
                appendFluidIngredient(ingredients, fluidIngredient, loops);
            }
        }

        if (!ingredients.isEmpty()) {
            return ingredients;
        }
        return GenericIngredient.ofRecipe(recipe);
    }

    private static void appendItemIngredient(List<GenericIngredient> ingredients, net.minecraft.world.item.crafting.Ingredient ingredient, int multiplier) {
        if (ingredient == null || ingredient.isEmpty()) {
            return;
        }

        List<GenericStack> options = Arrays.stream(ingredient.getItems())
                .filter(stack -> !stack.isEmpty())
                .map(stack -> GenericStack.wrap(stack.copy()))
                .filter(stack -> !stack.isEmpty())
                .toList();
        if (options.isEmpty()) {
            return;
        }

        int baseRequired = Math.max(1, options.stream().mapToInt(GenericStack::amount).max().orElse(1));
        int requiredAmount = Math.max(1, baseRequired * Math.max(1, multiplier));
        ingredients.add(new TransferSlotIngredient(options, requiredAmount));
    }

    private static void appendFluidIngredient(List<GenericIngredient> ingredients, FluidIngredient fluidIngredient, int multiplier) {
        if (fluidIngredient == null) {
            return;
        }

        List<GenericStack> options = fluidIngredient.getMatchingFluidStacks()
                .stream()
                .filter(fluidStack -> !fluidStack.isEmpty())
                .map(PortableStockTickerTransferHandler::createFluidlogisticsVirtualTankStack)
                .filter(stack -> !stack.isEmpty())
                .toList();
        if (options.isEmpty()) {
            return;
        }

        int baseRequired = Math.max(1, options.stream().mapToInt(GenericStack::amount).max().orElse(1));
        int requiredAmount = Math.max(1, baseRequired * Math.max(1, multiplier));
        ingredients.add(new TransferSlotIngredient(options, requiredAmount));
    }

    private Optional<GenericStack> convertToTransferStack(mezz.jei.api.ingredients.ITypedIngredient<?> typedIngredient) {
        Object ingredient = typedIngredient.getIngredient();
        if (ingredient instanceof FluidStack fluidStack && !fluidStack.isEmpty()) {
            GenericStack virtualTankStack = createFluidlogisticsVirtualTankStack(fluidStack);
            if (!virtualTankStack.isEmpty()) {
                return Optional.of(virtualTankStack);
            }
        }
        return IngredientTransfer.tryConvert(jeiHelpers.getIngredientManager(), typedIngredient);
    }

    private static GenericStack createFluidlogisticsVirtualTankStack(FluidStack fluidStack) {
        net.minecraft.resources.ResourceLocation id = new net.minecraft.resources.ResourceLocation("fluidlogistics", "compressed_storage_tank");
        net.minecraft.world.item.Item tankItem = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id);
        if (tankItem == null) {
            return GenericStack.EMPTY;
        }

        ItemStack stack = new ItemStack(tankItem);
        CompoundTag tag = stack.getOrCreateTag();
        CompoundTag fluidTag = fluidStack.writeToNBT(new CompoundTag());
        if (fluidTag == null) {
            return GenericStack.EMPTY;
        }

        tag.put("Fluid", fluidTag);
        tag.putBoolean("Virtual", true);
        tag.remove("Identity");
        return GenericStack.wrap(stack).withAmount(Math.max(1, fluidStack.getAmount()));
    }

    private record TransferSlotIngredient(List<GenericStack> options, int amount) implements GenericIngredient {
        @Override
        public boolean isEmpty() {
            return options.isEmpty() || amount <= 0;
        }

        @Override
        public boolean test(GenericStack stack) {
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            for (GenericStack option : options) {
                if (option.canStack(stack) || matchesFluidlogisticsVirtualTank(option, stack)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean matchesFluidlogisticsVirtualTank(GenericStack option, GenericStack candidate) {
            if (!(option.key() instanceof ItemKey optionItemKey) || !(candidate.key() instanceof ItemKey candidateItemKey)) {
                return false;
            }

            ItemStack optionStack = optionItemKey.stack();
            ItemStack candidateStack = candidateItemKey.stack();
            if (!isVirtualCompressedTank(optionStack) || !isVirtualCompressedTank(candidateStack)) {
                return false;
            }

            FluidStack optionFluid = readFluidFromCompressedTank(optionStack);
            FluidStack candidateFluid = readFluidFromCompressedTank(candidateStack);
            return !optionFluid.isEmpty() && !candidateFluid.isEmpty() && candidateFluid.isFluidEqual(optionFluid);
        }
    }

    private static @Nonnull RegistryAccess requireRegistryAccess(Level level) {
        RegistryAccess registryAccess = level.registryAccess();
        if (registryAccess == null) {
            throw new IllegalStateException("RegistryAccess is unexpectedly null");
        }
        return registryAccess;
    }

    private static class PortableCompatibleCraftableStack extends CraftableBigItemStack implements CraftableGenericStack {
        private final List<GenericIngredient> transferIngredients;

        public PortableCompatibleCraftableStack(ItemStack stack, Recipe<?> recipe, List<GenericIngredient> transferIngredients) {
            super(stack, recipe);
            this.transferIngredients = transferIngredients;
        }

        @Override
        public List<GenericIngredient> ingredients() {
            return transferIngredients;
        }

        @Override
        public List<GenericStack> results(RegistryAccess registryAccess) {
            return List.of(GenericStack.wrap(recipe.getResultItem(requireRegistryAccess(registryAccess))));
        }

        @Override
        public int outputCount(Level level) {
            return getOutputCount(level);
        }

        @Override
        public CraftableBigItemStack asStack() {
            return this;
        }

        @Override
        public GenericStack get() {
            return GenericStack.wrap(stack).withAmount(count);
        }

        @Override
        public void set(GenericStack genericStack) {
            if (genericStack.key() instanceof ItemKey itemKey) {
                this.stack = itemKey.stack();
            } else {
                this.stack = ItemStack.EMPTY;
            }
            this.count = genericStack.amount();
        }

        @Override
        public void setAmount(int amount) {
            this.count = amount;
        }
    }

    private static @Nonnull RegistryAccess requireRegistryAccess(RegistryAccess registryAccess) {
        if (registryAccess == null) {
            throw new IllegalStateException("RegistryAccess is unexpectedly null");
        }
        return registryAccess;
    }
}
