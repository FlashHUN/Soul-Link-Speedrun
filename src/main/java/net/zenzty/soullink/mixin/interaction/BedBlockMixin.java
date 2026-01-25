package net.zenzty.soullink.mixin.interaction;

import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(BedBlock.class)
public class BedBlockMixin {

    @ModifyArgs(method = "onUse", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;createExplosion(Lnet/minecraft/entity/Entity;Lnet/minecraft/entity/damage/DamageSource;Lnet/minecraft/world/explosion/ExplosionBehavior;Lnet/minecraft/util/math/Vec3d;FZLnet/minecraft/world/World$ExplosionSourceType;)V"))
    public void modifyArgs_onUse_createExplosion(Args args, BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        DamageSource source = args.get(1);
        source.attacker = player;
        args.set(1, source);
    }

}
