package com.justenoughcouriers.client;

import com.justenoughcouriers.JustEnoughCouriers;
import com.kreidev.cmpackagecouriers.stock_ticker.PortableStockTickerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import ru.zznty.create_factory_abstractions.generic.support.CraftableGenericStack;

import java.lang.reflect.Field;
import java.util.List;

@Mod.EventBusSubscriber(modid = JustEnoughCouriers.MOD_ID, value = Dist.CLIENT)
public class PortableStockTickerScrollHook {
    private static final int RECIPE_ROW_OFFSET_Y = 31;

    @SubscribeEvent
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!(event.getScreen() instanceof PortableStockTickerScreen screen)) {
            return;
        }

        List<CraftableGenericStack> recipes = screen.recipesToOrder();
        if (recipes.isEmpty()) {
            return;
        }

        int windowWidth = readIntField(screen, "windowWidth", 200);
        int colWidth = readIntField(screen, "colWidth", 20);
        int rowHeight = readIntField(screen, "rowHeight", 20);
        int orderY = readIntField(screen, "orderY", screen.getGuiTop() + 80);

        int recipesX = screen.getGuiLeft() + (windowWidth - colWidth * recipes.size()) / 2 + 1;
        int recipesY = orderY - RECIPE_ROW_OFFSET_Y;

        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();
        if (mouseX < recipesX || mouseX >= recipesX + colWidth * recipes.size()) {
            return;
        }
        if (mouseY < recipesY || mouseY >= recipesY + rowHeight) {
            return;
        }

        int index = (int) ((mouseX - recipesX) / colWidth);
        if (index < 0 || index >= recipes.size()) {
            return;
        }

        double delta = event.getScrollDelta();
        CraftableGenericStack craftable = recipes.get(index);

        // When count is already 0, scrolling down in the native handler removes the entry entirely.
        // Intercept and swallow the event to match Create's behavior of keeping the icon at 0.
        if (delta < 0 && craftable.get().amount() == 0) {
            event.setCanceled(true);
            return;
        }

        int transfer = Mth.ceil(Math.abs(delta)) * (Screen.hasControlDown() ? 10 : 1);
        if (transfer <= 0) {
            return;
        }

        screen.requestCraftable(craftable, delta < 0 ? -transfer : transfer);
        event.setCanceled(true);
    }

    private static int readIntField(Object target, String fieldName, int fallback) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException ignored) {
            return fallback;
        }
    }
}
