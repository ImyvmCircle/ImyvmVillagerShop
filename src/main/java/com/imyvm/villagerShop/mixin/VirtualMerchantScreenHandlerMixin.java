package com.imyvm.villagerShop.mixin;

import com.imyvm.economy.EconomyMod;
import com.imyvm.villagerShop.apis.Translator;
import eu.pb4.sgui.api.gui.MerchantGui;
import eu.pb4.sgui.virtual.merchant.VirtualMerchant;
import eu.pb4.sgui.virtual.merchant.VirtualMerchantScreenHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.village.MerchantInventory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VirtualMerchantScreenHandler.class)
public abstract class VirtualMerchantScreenHandlerMixin {
    @Shadow @Final private MerchantInventory merchantInventory;

    @Shadow(remap = false) public abstract MerchantGui getGui();

    @Shadow(remap = false) @Final private VirtualMerchant merchant;

    @Inject(method = "selectNewTrade", at = @At("HEAD"), cancellable = true, remap = false)
    private void selectNewTrade(int tradeIndex, CallbackInfo ci) {
        this.merchantInventory.setOfferIndex(tradeIndex);
        this.getGui().onSelectTrade(this.merchant.getOffers().get(tradeIndex));
        this.merchantInventory.removeStack(0);
        this.merchantInventory.removeStack(1);

        if (this.merchant.getOffers().size() > tradeIndex && this.merchant.getOffers().get(tradeIndex).getFirstBuyItem().item().value() == Items.BAMBOO) {
            var imyvmCurry = this.merchant.getOffers().get(tradeIndex).getFirstBuyItem().itemStack();
            this.merchantInventory.setStack(0, imyvmCurry);
            var customData = imyvmCurry.get(DataComponentTypes.CUSTOM_DATA);
            if (customData != null && customData.contains("securityCode")) {
                var moneyShouldTake = imyvmCurry.get(DataComponentTypes.DAMAGE);
                var player = this.getGui().getPlayer();
                var playerBalance = EconomyMod.data.getOrCreate(player).getMoney();
                if (moneyShouldTake != null && playerBalance < moneyShouldTake * 100) {
                    var barrierItem = Registries.ITEM.getOrEmpty(Identifier.tryParse("minecraft:barrier")).orElseThrow();
                    var barrierItemStack = barrierItem.getDefaultStack();
                    barrierItemStack.set(DataComponentTypes.CUSTOM_NAME, Translator.INSTANCE.tr("shop.buy.money.lack"));
                    barrierItemStack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
                    this.merchantInventory.setStack(1, barrierItemStack);
                }
            }
            ci.cancel();
        }
    }

    @Inject(method = "onClosed", at = @At(value = "INVOKE", target = "net/minecraft/entity/player/PlayerEntity.getWorld ()Lnet/minecraft/world/World;"), cancellable = true)
    private void onClosed(PlayerEntity playerEntity, CallbackInfo ci) {
        if (!playerEntity.getWorld().isClient && playerEntity instanceof ServerPlayerEntity) {
            var firstItemStack = this.merchantInventory.getStack(0);
            var firstItemStackCustomData = firstItemStack.get(DataComponentTypes.CUSTOM_DATA);
            if (firstItemStack.getItem() == Items.BAMBOO && firstItemStackCustomData != null && firstItemStackCustomData.contains("securityCode")) {
                this.merchantInventory.removeStack(0);
                this.merchantInventory.removeStack(1);
                this.merchantInventory.removeStack(2);
                ci.cancel();
            }
        }
    }

}
