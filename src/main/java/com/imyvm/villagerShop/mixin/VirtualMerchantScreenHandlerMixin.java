package com.imyvm.villagerShop.mixin;

import com.imyvm.economy.EconomyMod;
import com.imyvm.villagerShop.apis.Translator;
import eu.pb4.sgui.api.gui.MerchantGui;
import eu.pb4.sgui.impl.virtual.merchant.VirtualMerchant;
import eu.pb4.sgui.impl.virtual.merchant.VirtualMerchantScreenHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MerchantContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VirtualMerchantScreenHandler.class)
public abstract class VirtualMerchantScreenHandlerMixin {
    @Shadow @Final private MerchantContainer merchantInventory;

    @Shadow(remap = false) public abstract MerchantGui getGui();

    @Shadow(remap = false) @Final private VirtualMerchant merchant;

    @Inject(method = "selectNewTrade", at = @At("HEAD"), cancellable = true, remap = false)
    private void selectNewTrade(int tradeIndex, CallbackInfo ci) {
        this.merchantInventory.setSelectionHint(tradeIndex);
        this.getGui().onSelectTrade(this.merchant.getOffers().get(tradeIndex));
//        this.merchantInventory.removeItem(0);
//        this.merchantInventory.removeItem(1);
        this.merchantInventory.clearContent();

        if (this.merchant.getOffers().size() > tradeIndex && this.merchant.getOffers().get(tradeIndex).getCostA().getItem() == Items.BAMBOO) {
            var imyvmCurry = this.merchant.getOffers().get(tradeIndex).getCostA();
//            this.merchantInventory.setStack(0, imyvmCurry);
            this.merchantInventory.setItem(0, imyvmCurry);
            var customData = imyvmCurry.get(DataComponents.CUSTOM_DATA);
            if (customData != null && customData.copyTag().contains("securityCode")) {
                var moneyShouldTake = imyvmCurry.get(DataComponents.DAMAGE);
                var player = this.getGui().getPlayer();
                var playerBalance = EconomyMod.data.getOrCreate(player).getMoney();
                if (moneyShouldTake != null && playerBalance < moneyShouldTake * 100L) {
                    var barrierItem = Items.BARRIER;
                    var barrierItemStack = barrierItem.getDefaultInstance();
                    barrierItemStack.set(DataComponents.CUSTOM_NAME, Translator.INSTANCE.tr("shop.buy.money.lack"));
                    barrierItemStack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                    this.merchantInventory.setItem(1, barrierItemStack);
                }
            }
            ci.cancel();
        }
    }
}
