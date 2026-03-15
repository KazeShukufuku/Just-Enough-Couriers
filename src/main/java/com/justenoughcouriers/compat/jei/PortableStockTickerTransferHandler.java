package com.justenoughcouriers.compat.jei;

import com.kreidev.cmpackagecouriers.stock_ticker.PortableStockTickerMenu;
import com.kreidev.cmpackagecouriers.stock_ticker.PortableStockTickerScreen;
import com.simibubi.create.content.logistics.stockTicker.CraftableBigItemStack;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import java.util.Optional;
import ru.zznty.create_factory_abstractions.api.generic.stack.GenericIngredient;
import ru.zznty.create_factory_abstractions.api.generic.stack.GenericStack;
import ru.zznty.create_factory_abstractions.compat.jei.IngredientTransfer;
import ru.zznty.create_factory_abstractions.compat.jei.TransferOperationsResult;
import ru.zznty.create_factory_abstractions.generic.key.item.ItemKey;
import ru.zznty.create_factory_abstractions.generic.support.CraftableGenericStack;
import ru.zznty.create_factory_abstractions.generic.support.GenericInventorySummary;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PortableStockTickerTransferHandler implements IUniversalRecipeTransferHandler<PortableStockTickerMenu> {
    private static final int MAX_INGREDIENT_SLOTS = 9;

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

        if (!transferOperations.missingItems().isEmpty()) {
            return transferHelper.createUserErrorForMissingSlots(
                    Objects.requireNonNull(Component.translatable("create.gui.stock_keeper.not_in_stock")),
                    Objects.requireNonNull(transferOperations.missingItems())
            );
        }

        if (!doTransfer) {
            return null;
        }

        ItemStack recipeResult = recipe.getResultItem(requireRegistryAccess(player.level()));
        if (recipeResult.isEmpty()) {
            return transferHelper.createUserErrorWithTooltip(Objects.requireNonNull(Component.translatable("create.gui.stock_keeper.recipe_result_empty")));
        }

        CraftableGenericStack craftable = new PortableCompatibleCraftableStack(recipeResult.copy(), recipe);
        screen.recipesToOrder().add(craftable);
        if (screen.searchBox != null) {
            screen.searchBox.setValue("");
        }
        screen.refreshSearchNextTick = true;

        int requestAmount = maxTransfer ? recipeResult.getMaxStackSize() : 1;
        screen.requestCraftable(craftable, requestAmount);
        return null;
    }

    private static @Nonnull RegistryAccess requireRegistryAccess(Level level) {
        RegistryAccess registryAccess = level.registryAccess();
        if (registryAccess == null) {
            throw new IllegalStateException("RegistryAccess is unexpectedly null");
        }
        return registryAccess;
    }

    private static class PortableCompatibleCraftableStack extends CraftableBigItemStack implements CraftableGenericStack {
        public PortableCompatibleCraftableStack(ItemStack stack, Recipe<?> recipe) {
            super(stack, recipe);
        }

        @Override
        public List<GenericIngredient> ingredients() {
            return GenericIngredient.ofRecipe(recipe);
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
