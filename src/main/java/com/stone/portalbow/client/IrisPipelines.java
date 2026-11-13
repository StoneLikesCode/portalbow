package com.stone.portalbow.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Gives each portal view its own copy of the shader pack's machinery when Iris is installed.
 *
 * <p>A shader pack remembers things from one frame to the next (the previous picture, where the
 * camera was, its shadow maps) and assumes there is only ever one camera. If the portal view and
 * the normal view shared that memory, each would smear into the other. Iris keeps all of it in
 * one object per dimension, a "pipeline". So for each portal view we ask Iris to build a second
 * pipeline, slot it in while the portal view is drawn, and put the real one back afterwards.
 *
 * <p>Building a pipeline compiles the whole shader pack, which freezes the game for a moment, so
 * spares are kept and handed from one portal to the next rather than built for every new portal.
 *
 * <p>Iris has no official way to do this, so we reach into its private parts by name
 * ("reflection"). THIS IS THE FRAGILE BIT: it was written against Iris 1.11.7 and an Iris update
 * may rename these things. If that happens {@link TrueViews} notices the failure, switches the
 * real view off and falls back to the simpler picture; the game keeps running.
 */
final class IrisPipelines {
	private static final boolean IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");

	private static Method getPipelineManager;
	private static Method getCurrentDimension;
	private static Method preparePipeline;
	private static Method getVersion;
	private static Method destroy;
	private static Field pipelinesPerDimension;
	private static Field pipelineFactory;
	private static Field initializedBlockIds;
	private static Class<?> shaderPipelineClass;

	/** This many spares are kept ready however long they sit idle; any beyond that are freed. */
	private static final int SPARES_TO_KEEP = 2;
	/** A spare whose portal has not been drawn for this long may be handed to another portal. */
	private static final long REUSE_AFTER_NANOS = 1_000_000_000L;
	/** A surplus spare idle for this long is freed (they use a lot of video memory). */
	private static final long FREE_AFTER_NANOS = 20_000_000_000L;

	private static final List<Spare> SPARES = new ArrayList<>();

	private static Object manager;
	private static Object dimension;
	private static Object realPipeline;

	private static final class Spare {
		final Object pipeline;
		final Object dimension;
		final int version;
		/** The portal this spare is currently drawing for. */
		int portalId;
		long usedAt;

		Spare(Object pipeline, Object dimension, int version) {
			this.pipeline = pipeline;
			this.dimension = dimension;
			this.version = version;
		}
	}

	private IrisPipelines() {
	}

	private static void lookUp() throws ReflectiveOperationException {
		if (getPipelineManager != null) {
			return;
		}
		Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
		Class<?> managerClass = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
		Class<?> pipelineClass = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPipeline");
		Class<?> idClass = Class.forName("net.irisshaders.iris.shaderpack.materialmap.NamespacedId");
		shaderPipelineClass = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
		getCurrentDimension = iris.getMethod("getCurrentDimension");
		preparePipeline = managerClass.getMethod("preparePipeline", idClass);
		getVersion = managerClass.getMethod("getVersionCounterForSodiumShaderReload");
		destroy = pipelineClass.getMethod("destroy");
		pipelinesPerDimension = managerClass.getDeclaredField("pipelinesPerDimension");
		pipelinesPerDimension.setAccessible(true);
		pipelineFactory = managerClass.getDeclaredField("pipelineFactory");
		pipelineFactory.setAccessible(true);
		initializedBlockIds = shaderPipelineClass.getDeclaredField("initializedBlockIds");
		initializedBlockIds.setAccessible(true);
		getPipelineManager = iris.getMethod("getPipelineManager");
	}

	/** Slots in the spare pipeline for this portal. Always follow with {@link #end()}. */
	@SuppressWarnings("unchecked")
	static void begin(int portalId) throws ReflectiveOperationException {
		if (!IRIS_LOADED) {
			return;
		}
		lookUp();
		Object pipelineManager = getPipelineManager.invoke(null);
		Object currentDimension = getCurrentDimension.invoke(null);
		Map<Object, Object> pipelines = (Map<Object, Object>) pipelinesPerDimension.get(pipelineManager);
		Object real = pipelines.get(currentDimension);
		if (!shaderPipelineClass.isInstance(real)) {
			return; // Shaders are switched off (or not set up yet): nothing needs separating.
		}

		int version = (int) getVersion.invoke(pipelineManager);
		long now = System.nanoTime();
		// A reloaded shader pack or a change of dimension leaves the old spares out of date.
		for (int i = SPARES.size() - 1; i >= 0; i--) {
			Spare old = SPARES.get(i);
			if (old.version != version || !old.dimension.equals(currentDimension)) {
				destroy.invoke(old.pipeline);
				SPARES.remove(i);
			}
		}

		Spare spare = null;
		Spare idle = null;
		for (Spare candidate : SPARES) {
			if (candidate.portalId == portalId) {
				spare = candidate;
				break;
			}
			if (now - candidate.usedAt > REUSE_AFTER_NANOS && (idle == null || candidate.usedAt < idle.usedAt)) {
				idle = candidate;
			}
		}
		if (spare == null && idle != null) {
			// Take over a spare nobody is looking through. It still remembers the other portal's
			// last picture, which a shader pack may blend in for a few frames; far better than
			// freezing the game to build a new one.
			spare = idle;
			spare.portalId = portalId;
		}
		if (spare == null) {
			Object built = ((Function<Object, Object>) pipelineFactory.get(pipelineManager)).apply(currentDimension);
			if (!shaderPipelineClass.isInstance(built)) {
				throw new IllegalStateException("Iris could not build a second shader pipeline");
			}
			// The first time a pipeline draws, it works out the pack's block IDs and then makes the
			// game rebuild every chunk in the world to use them. The real pipeline has already done
			// that with the very same pack, so tell the spare it is done; otherwise every new spare
			// reloads the whole world.
			if (initializedBlockIds.getBoolean(real)) {
				initializedBlockIds.setBoolean(built, true);
			}
			spare = new Spare(built, currentDimension, version);
			spare.portalId = portalId;
			SPARES.add(spare);
		}
		spare.usedAt = now;

		pipelines.put(currentDimension, spare.pipeline);
		preparePipeline.invoke(pipelineManager, currentDimension);
		manager = pipelineManager;
		dimension = currentDimension;
		realPipeline = real;
	}

	/** Puts the real pipeline back. Safe to call even if {@link #begin} did nothing. */
	@SuppressWarnings("unchecked")
	static void end() {
		if (manager == null) {
			return;
		}
		try {
			((Map<Object, Object>) pipelinesPerDimension.get(manager)).put(dimension, realPipeline);
			preparePipeline.invoke(manager, dimension);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Could not hand the shader pipeline back to Iris", e);
		} finally {
			manager = null;
			dimension = null;
			realPipeline = null;
		}
	}

	/** Frees spare pipelines that are out of date, and surplus ones nobody has looked through lately. */
	static void freeUnused() {
		if (SPARES.isEmpty()) {
			return;
		}
		long now = System.nanoTime();
		try {
			int version = (int) getVersion.invoke(getPipelineManager.invoke(null));
			SPARES.sort(Comparator.comparingLong((Spare spare) -> spare.usedAt).reversed());
			for (int i = SPARES.size() - 1; i >= 0; i--) {
				Spare spare = SPARES.get(i);
				boolean surplus = i >= SPARES_TO_KEEP && now - spare.usedAt > FREE_AFTER_NANOS;
				if (spare.version != version || surplus) {
					SPARES.remove(i);
					destroy.invoke(spare.pipeline);
				}
			}
		} catch (ReflectiveOperationException ignored) {
			// Nothing useful to do; try again next tick.
		}
	}

	/** Frees every spare, for when we leave the world. */
	static void clear() {
		for (Spare spare : SPARES) {
			try {
				destroy.invoke(spare.pipeline);
			} catch (ReflectiveOperationException ignored) {
				// Drop our reference either way.
			}
		}
		SPARES.clear();
	}
}
