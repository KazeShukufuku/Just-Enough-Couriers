package com.justenoughcouriers.compat.jei;

import com.justenoughcouriers.JustEnoughCouriers;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nonnull;

@JeiPlugin
public class JecJeiPlugin implements IModPlugin {
    private static final ResourceLocation ID = new ResourceLocation(JustEnoughCouriers.MOD_ID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return ID;
    }

    @Override
    public void registerRecipeTransferHandlers(@Nonnull IRecipeTransferRegistration registration) {
        registration.addUniversalRecipeTransferHandler(new PortableStockTickerTransferHandler(
                registration.getJeiHelpers(),
                registration.getTransferHelper()
        ));
    }
}
