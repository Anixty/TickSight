package net.anixty.ticksight.client

import net.anixty.ticksight.util.locationId

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.Vec3
import net.anixty.ticksight.TickSight
import kotlin.math.pow
import kotlin.math.roundToInt

//? if <26.1 {
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.*
import net.minecraft.client.Camera
import net.minecraft.client.gui.Font.DisplayMode
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import org.joml.Matrix4f
//?}

//? if >=1.21.4 && <26.1 {
/*import net.minecraft.client.renderer.CoreShaders
import com.mojang.blaze3d.buffers.BufferUsage*/
//?}

//? if >=26.1 {
/*import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.TextGizmo*/
//?}
object Overlay {
    data class Color(val r: Int, val g: Int, val b: Int, val a: Int) {
        companion object {
            fun fromNanos(rateNanos: Double): Color {
                val micros = rateNanos / 1000.0
                return Color(micros)
            }
        }

        constructor(
            rateMicros: Double
        ) : this(
            (rateMicros / 100.0 * 255).roundToInt().coerceIn(0, 255),
            ((100.0 - rateMicros) / 100.0 * 255).roundToInt().coerceIn(0, 255),
            0,
            (rateMicros / 100.0 * 255).roundToInt().coerceIn(20, 100)
        )

        val hex: Int =
            with(this) {
                val red = if (r > g) 0xFFu else (255 * r / g).toUInt()
                val green = if (g > r) 0xFFu else (255 * g / r).toUInt()
                (red shl 16) or (green shl 8) or (0xFFu shl 24)
            }
                .toInt()
    }

    sealed class Entry(val color: Color) {
        data class EntityEntry(val entityId: Int, val rate: Double) : Entry(Color.fromNanos(rate)) {
            val entity
                get() = Minecraft.getInstance().level?.getEntity(entityId)
        }

        data class BlockEntry(val pos: BlockPos, val rate: Double) : Entry(Color.fromNanos(rate))
    }

    var enabled = true
    var entities: List<Entry.EntityEntry> = ArrayList()
    var blocks: List<Entry.BlockEntry> = ArrayList()
    var blockMap = mapOf<ChunkPos, List<Entry.BlockEntry>>()
    lateinit var loc: Vec3

//? if <26.1 {
    var vertexBuf: VertexBuffer? = null
//?}

    var vertexBufPosition: Vec3 = Vec3.ZERO
    var dataAvailable = false

    val DIST_FAC = 1.0 / (2 * 16.0.pow(2)).pow(.5)

    val font: Font by lazy { Minecraft.getInstance().font }

//? if <26.1 {
    class OverlayRenderType(name: String, fmt: VertexFormat, mode: VertexFormat.Mode) :
        RenderType(
            name,
            fmt,
            VertexFormat.Mode.QUADS,
            256,
            false,
            true,
            {},
            {}
        ) {
        companion object {
            fun build(): RenderType {
                val boolType = java.lang.Boolean.TYPE

                // So here's the thing: for some reason, Minecraft decided to not allow
                // any kind of external access to  create a custom RenderType outside
                // the class. However, we need to make our own to have the block outlines
                // visible through walls. We can't mixin an invoker either as the
                // CompositeRenderType is private within RenderType.
                // We can get around that using reflection, hence this monstrosity.
                val parameterTypes =
                    arrayOf(
                        String::class.java,
                        VertexFormat::class.java,
                        VertexFormat.Mode::class.java,
                        Integer.TYPE,
                        boolType,
                        boolType,
                        RenderType.CompositeState::class.java
                    )
                val fn =
                    RenderType::class
                        .java
                        .declaredMethods
                        .filter { method -> method.parameterTypes.contentEquals(parameterTypes) }
                        .first()
                fn.isAccessible = true

                return fn.invoke(
                    null,
                    "heat",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    false,
                    buildCompositeState()
                ) as RenderType
            }

            private fun buildCompositeState(): CompositeState {
                return RenderType.CompositeState.builder()
                    .setShaderState(
//? if >=1.21.4 {
/*ShaderStateShard(CoreShaders.POSITION_COLOR)*/
//?} else {
ShaderStateShard { GameRenderer.getPositionColorShader() }
//?}
)
                    //                    .setTextureState(EmptyTextureStateShard({}, {}))
                    .setDepthTestState(DepthTestStateShard("always", 519))
                    .setTransparencyState(
                        RenderStateShard.TransparencyStateShard(
                            "src_to_one",
                            {
                                RenderSystem.enableBlend()
                                RenderSystem.blendFunc(
                                    GlStateManager.SourceFactor.SRC_ALPHA,
                                    GlStateManager.DestFactor.ONE
                                )
                            }
                        ) {
                            RenderSystem.disableBlend()
                            RenderSystem.defaultBlendFunc()
                        }
                    )
                    .createCompositeState(true)
            }
        }
    }

    @Suppress("INACCESSIBLE_TYPE")
    private val renderType: RenderType by lazy { OverlayRenderType.build() }

//? if >=1.21.4 {
/*    private val compiledShader by lazy { Minecraft.getInstance().getShaderManager().getProgram(CoreShaders.POSITION_COLOR) }*/
//?}

//?}
    fun load(lvl: ClientLevel? = null) {
        val data = TickSightClient.results ?: return
        val level = lvl ?: Minecraft.getInstance().level ?: return
        val levelLocation = level.dimension().locationId()
        val ticks = data.ticks
        val norm = ClientSettings.normalized
        entities =
            data.entities[levelLocation]
                ?.map {
                    Entry.EntityEntry(
                        it.entityId!!,
                        if (data.peakMode) it.peak.toDouble()
                        else it.rate * (if (norm && ticks > 0) it.ticks.toDouble() / ticks else 1.0)
                    )
                }
                .orEmpty()
                .filter { it.rate >= ClientSettings.minRate }
                .sortedByDescending { it.rate }

        blocks =
            data.blocks[levelLocation]
                ?.map {
                    Entry.BlockEntry(
                        it.position,
                        if (data.peakMode) it.peak.toDouble()
                        else it.rate * (if (norm && ticks > 0) it.ticks.toDouble() / ticks else 1.0)
                    )
                }
                ?.filter { it.rate >= ClientSettings.minRate }
                .orEmpty()
        blockMap = blocks.groupBy { ChunkPos(it.pos.x shr 4, it.pos.z shr 4) }

        dataAvailable = true
    }

    inline fun loadSync(lvl: ClientLevel? = null) = synchronized(this) { this.load(lvl) }

//? if <26.1 {
    fun render(poseStack: PoseStack, partialTicks: Float, projection: Matrix4f) {
        if (!enabled || TickSightClient.results == null) return

        val camera = Minecraft.getInstance().gameRenderer.mainCamera
        val bufSrc = Minecraft.getInstance().renderBuffers().bufferSource()

        RenderSystem.disableDepthTest()
        RenderSystem.enableBlend()
        RenderSystem.blendFunc(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
        )

        poseStack.pushPose()

        if (dataAvailable || vertexBufPosition.distanceToSqr(camera.position) > 1_000_000) {
            createVBO(camera)
            dataAvailable = false
        }

        synchronized(this) {
            val cpos = ChunkPos(Minecraft.getInstance().player!!.blockPosition())
            val dist = (ClientSettings.maxBlockDist / 16).coerceAtLeast(2)
            for (x in (cpos.x - dist)..(cpos.x + dist)) {
                for (y in (cpos.z - dist)..(cpos.z + dist)) {
                    blockMap[ChunkPos(x, y)]?.forEach { entry ->
                        val maxDist = ClientSettings.maxBlockDist * ClientSettings.maxBlockDist
                        if (camera.blockPosition.distSqr(entry.pos) < maxDist) {
                            drawBlock(entry, poseStack, camera, bufSrc)
                        }
                    }
                }
            }

            val maxEntityIndex = ClientSettings.maxEntityCount - 1
            for ((i, entry) in entities.withIndex()) {
                if (i > maxEntityIndex) break
                drawEntity(entry, poseStack, partialTicks, camera, bufSrc)
            }

//? if >=1.21 {
            poseStack.mulPose(org.joml.Quaternionf(camera.rotation()).conjugate())
//?}

            vertexBufPosition.subtract(camera.position).apply {
                poseStack.translate(x, y, z)
            }

            vertexBuf?.let {
                it.bind()
                it.drawWithShader(
                    poseStack.last().pose(),
                    projection,

//? if >=1.21.4 {
/*compiledShader*/
//?} else {
GameRenderer.getPositionColorShader()!!
//?}

                )
                VertexBuffer.unbind()
            }
        }

        poseStack.popPose()
        bufSrc.endBatch()

        // Cleanup
        RenderSystem.enableDepthTest()
    }

    fun createVBO(camera: Camera) {
        TickSight.LOGGER.info("Initializing VBO")
        vertexBuf?.close()
        vertexBuf = null
        if (blocks.isEmpty()) {
            vertexBufPosition = camera.position
            return
        }
        val buf =
//? if <1.21 {
/*BufferBuilder(renderType.bufferSize() * blocks.size).also { it.begin(renderType.mode(), renderType.format()) }*/
//?} else {
BufferBuilder(ByteBufferBuilder(renderType.bufferSize() * blocks.size), renderType.mode(), renderType.format())
//?}

        var stack = PoseStack()

        for (entry in blocks.filter { block -> block.pos.distSqr(camera.blockPosition) < 1_440_000 }) {
            drawBlockOutline(entry, stack, camera, buf)
        }

        val rendered =
//? if <1.21 {
/*buf.end()*/
//?} else {
buf.build() ?: return
//?}

        val vbuf = VertexBuffer(
//? if >=1.21.4 {
/*BufferUsage.DYNAMIC_WRITE*/
//?} else {
VertexBuffer.Usage.DYNAMIC
//?}
)
        vbuf.bind()
        vbuf.upload(rendered)
        VertexBuffer.unbind()
        vertexBuf = vbuf
        vertexBufPosition = camera.position
        dataAvailable = false
    }

    inline fun drawEntity(
        entry: Entry.EntityEntry,
        poseStack: PoseStack,
        partialTicks: Float,
        camera: Camera,
        bufSrc: MultiBufferSource
    ) {
        val rate = entry.rate
        val entity = entry.entity ?: return
        if (entity.isRemoved) return

        var text = "${(rate / 1000).roundToInt()} μs/t"
        val pos = entity.getPosition(partialTicks)
        if (camera.position.distanceTo(pos) > ClientSettings.maxEntityDist) return
        poseStack.pushPose()
        if (!entity.isAlive) {
            text += " [X]"
        }

        pos.subtract(camera.position).apply {
            poseStack.translate(x, y + entity.bbHeight + 0.33, z)
            poseStack.mulPose(camera.rotation())
            poseStack.scale(
//? if <1.21 {
/*-0.025F*/
//?} else {
0.025F
//?}
, -0.025F, 0.025F)
            font.drawInBatch(
                text,
                -font.width(text).toFloat() / 2,
                0F,
                entry.color.hex,
                false,
                poseStack.last().pose(),
                bufSrc,
                DisplayMode.SEE_THROUGH,
                0,
                0xF000F0
            )
        }

        poseStack.popPose()
    }

    private fun drawBlockOutline(
        entry: Entry.BlockEntry,
        poseStack: PoseStack,
        camera: Camera,
        buf: VertexConsumer
    ) {
        poseStack.pushPose()

        Vec3.atLowerCornerOf(entry.pos).subtract(camera.position).apply { poseStack.translate(x, y, z) }
        val mat = poseStack.last().pose()
        entry.color.apply {
            fun vertex(x: Float, y: Float, z: Float) {
//? if <1.21 {
/*buf.vertex(mat, x, y, z).color(r, g, b, a).endVertex()*/
//?} else {
buf.addVertex(mat, x, y, z).setColor(r, g, b, a)
//?}
            }
            vertex(0F, 1F, 0F)
            vertex(0F, 1F, 1F)
            vertex(1F, 1F, 1F)
            vertex(1F, 1F, 0F)

            vertex(0F, 1F, 0F)
            vertex(1F, 1F, 0F)
            vertex(1F, 0F, 0F)
            vertex(0F, 0F, 0F)

            vertex(1F, 1F, 1F)
            vertex(0F, 1F, 1F)
            vertex(0F, 0F, 1F)
            vertex(1F, 0F, 1F)

            vertex(0F, 1F, 1F)
            vertex(0F, 1F, 0F)
            vertex(0F, 0F, 0F)
            vertex(0F, 0F, 1F)

            vertex(1F, 0F, 1F)
            vertex(1F, 0F, 0F)
            vertex(1F, 1F, 0F)
            vertex(1F, 1F, 1F)

            vertex(1F, 0F, 0F)
            vertex(1F, 0F, 1F)
            vertex(0F, 0F, 1F)
            vertex(0F, 0F, 0F)
        }

        poseStack.popPose()
    }

    private inline fun drawBlock(
        entry: Entry.BlockEntry,
        poseStack: PoseStack,
        camera: Camera,
        bufSrc: MultiBufferSource
    ) {
        poseStack.pushPose()

        val (pos, rate) = entry
        val text = "${(rate / 1000).roundToInt()} μs/t"

        val col: Int = -0x1
        Vec3.atCenterOf(pos).subtract(camera.position).apply {
            poseStack.translate(x, y, z)
            poseStack.mulPose(camera.rotation())
            poseStack.scale(
//? if <1.21 {
/*-0.025F*/
//?} else {
0.025F
//?}
, -0.025F, 0.025F)
            font.drawInBatch(
                text,
                -font.width(text).toFloat() / 2,
                0F,
                col,
                false,
                poseStack.last().pose(),
                bufSrc,
                DisplayMode.SEE_THROUGH,
                0,
                0xF000F0
            )
        }

        poseStack.popPose()
    }

//?}

//? if >=26.1 {
/*
    /** Called while vanilla's per-frame GizmoCollector is active. */
    fun renderModern() {
        if (!enabled || TickSightClient.results == null) return
        val client = Minecraft.getInstance()
        val level = client.level ?: return
        val cameraPosition = client.gameRenderer.mainCamera.position()
        val blockDistance = ClientSettings.maxBlockDist.toDouble().pow(2)
        val entityDistance = ClientSettings.maxEntityDist.toDouble().pow(2)
        synchronized(this) {
            for (entry in blocks) {
                val position = Vec3.atCenterOf(entry.pos)
                if (cameraPosition.distanceToSqr(position) > blockDistance) continue
                val color = entry.color
                val fill = (color.a shl 24) or (color.r shl 16) or (color.g shl 8) or color.b
                Gizmos.cuboid(entry.pos, GizmoStyle.strokeAndFill(color.hex, 1.0F, fill)).setAlwaysOnTop()
                Gizmos.billboardText(
                    "${(entry.rate / 1000).roundToInt()} μs/t", position,
                    TextGizmo.Style.forColorAndCentered(-1)
                ).setAlwaysOnTop()
            }
            for (entry in entities.take(ClientSettings.maxEntityCount.coerceAtLeast(0))) {
                val entity = level.getEntity(entry.entityId) ?: continue
                if (entity.isRemoved || cameraPosition.distanceToSqr(entity.position()) > entityDistance) continue
                Gizmos.billboardText(
                    "${(entry.rate / 1000).roundToInt()} μs/t",
                    entity.position().add(0.0, entity.bbHeight + 0.33, 0.0),
                    TextGizmo.Style.forColorAndCentered(entry.color.hex)
                ).setAlwaysOnTop()
            }
        }
    }
*/
//?}
}
