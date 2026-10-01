#!/bin/zsh
# 纯逻辑层的本地验证：没有 Android SDK 也能编译并跑测试。
#
# 为什么需要它：MaaRunner 之外的那些「决策逻辑」层（BetterSliding / 据点交易干员选择 /
# MapNavigator 参数 / 囤货 / 交付周期）都刻意不依赖 android.util.Log 与 JNA，
# 所以可以脱离设备、脱离 Android SDK 直接编译并跑断言。CI 当然也会跑它们，
# 但 CI 一轮要 5~8 分钟，本地这个只要十几秒。
#
# 它覆盖什么、不覆盖什么：
#   ✅ 纯逻辑层：编译 + JUnit 用例真实执行（反射跑 @Test，桩的断言是真实现）
#   ❌ MaaRunner 本身：依赖 JNA 与 Android，只能由 CI 编译验证
#
# 依赖：只有 ~/.gradle/caches 里已有的 jar（跑过一次 gradle 就有了）与本机 JDK。
# 用法：
#   scripts/verify_pure_logic.sh all        编译 + 类型检查 + 跑全部用例
#   scripts/verify_pure_logic.sh main       只编译纯逻辑层
#   scripts/verify_pure_logic.sh typecheck  只类型检查测试文件
#   scripts/verify_pure_logic.sh run        只跑用例
#
# 中间产物放 .tmp/verify/（gitignored）：早先放 /tmp 的副本被系统清理丢过一次。
set -e

REPO="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$REPO/app/src/main/java/com/aliothmoon/maafw/remote"
SUP="$REPO/app/src/main/java/com/aliothmoon/maafw/supplement"
TSUP="$REPO/app/src/test/java/com/aliothmoon/maafw/supplement"
TST="$REPO/app/src/test/java/com/aliothmoon/maafw/remote"
# 诊断报告的保留/截断策略是纯逻辑（不碰文件系统），也要能本机验证
DIAG="$REPO/app/src/main/java/com/aliothmoon/maafw/diagnostics"
TDIAG="$REPO/app/src/test/java/com/aliothmoon/maafw/diagnostics"
# 调试 CLI 的命令解析是纯逻辑（不碰 socket/Android），纳入本机验证
CLI="$REPO/app/src/main/java/com/aliothmoon/maafw/cli"
TCLI="$REPO/app/src/test/java/com/aliothmoon/maafw/cli"
PRIV="$REPO/app/src/main/java/com/aliothmoon/maafw/privileged"
TPRIV="$REPO/app/src/test/java/com/aliothmoon/maafw/privileged"
WORK="$REPO/.tmp/verify"
STUB="$WORK/jstub"
GC=~/.gradle/caches/modules-2/files-2.1

if [[ ! -d "$GC" ]]; then
  echo "找不到 gradle 缓存（$GC）。先在仓库里跑一次 ./gradlew 或 CI，让依赖下来。" >&2
  exit 1
fi

KC=$(find $GC -name 'kotlin-compiler-embeddable-2.3.0.jar' | head -1)
KS=$(find $GC -name 'kotlin-stdlib-2.3.0.jar' | head -1)
KR=$(find $GC -name 'kotlin-reflect-2.3.0.jar' | head -1)
KX=$(find $GC -name 'kotlinx-coroutines-core-jvm-1.9.0.jar' | head -1)
AN=$(find $GC -name 'annotations-13.0.jar' -path '*jetbrains*' | head -1)
KSJ=$(find $GC -name 'kotlinx-serialization-json-jvm-1.11.0.jar' | head -1)
KSC=$(find $GC -name 'kotlinx-serialization-core-jvm-1.11.0.jar' | head -1)
for jar in "$KC" "$KS" "$KR" "$KX" "$AN" "$KSJ" "$KSC"; do
  if [[ -z "$jar" || ! -f "$jar" ]]; then
    echo "缺少必要的 jar（kotlin 编译器/stdlib/reflect/serialization）。先跑一次 ./gradlew 让依赖下来。" >&2
    exit 1
  fi
done

JARS="$KC:$KS:$KR:$KX:$AN:$KSJ:$KSC"
COMPILE_CP="$KS:$KSJ:$KSC"

MAIN_FILES=(
  "$SRC/BetterSlidingSupport.kt"
  "$SRC/BetterSlidingParams.kt"
  "$SRC/BetterSlidingDecision.kt"
  "$SRC/BetterSlidingOverrides.kt"
  "$SRC/BetterSlidingOcr.kt"
  "$SRC/JsonTree.kt"
  "$SRC/BetterSlidingSession.kt"
  "$SRC/MaaJsonTree.kt"
  "$SRC/GoodsSupport.kt"
  "$SRC/OcrProbeSupport.kt"
  "$SRC/AutoStockpileSupport.kt"
  "$SRC/AutoStockStapleSupport.kt"
  "$SRC/OutpostData.kt"
  "$SRC/OutpostReserveSupport.kt"
  "$SRC/OutpostPrioritySupport.kt"
  "$SRC/ScheduleSupport.kt"
  "$SRC/OperatorOcrMatch.kt"
  "$SRC/OperatorDataset.kt"
  "$SRC/OperatorSelection.kt"
  "$SRC/OperatorCache.kt"
  "$SRC/OperatorMatching.kt"
  "$SRC/OperatorSession.kt"
  "$SRC/OperatorScan.kt"
  "$SRC/OperatorRecognitions.kt"
  "$SRC/OperatorRuntime.kt"
  "$SRC/MapNaviParam.kt"
  "$SRC/MapNavHeading.kt"
  "$SRC/MapNavControlPure.kt"
  "$SRC/MapNavWalkPure.kt"
  "$SRC/PipelineOverrideSupport.kt"
  "$SRC/ReceptionRoomSupport.kt"
  "$SRC/ItemTransferSupport.kt"
  "$SRC/IntelArchiveSupport.kt"
  "$SRC/ListCompleteSupport.kt"
  "$SRC/FailureCollectorSupport.kt"
  "$SRC/AutoSellSupport.kt"
  "$SRC/ItemQuantitySupport.kt"
  "$SRC/AutoEcoFarmSwipe.kt"
  "$SRC/AutoEcoFarmNearest.kt"
  "$SRC/AutoEcoFarmOverride.kt"
  "$SRC/AutoEcoFarmSleep.kt"
  "$SRC/AutoDeliverySupport.kt"
  "$SRC/OngoingDeliverySupport.kt"
  "$SRC/SeizeDeliverySupport.kt"
  "$SRC/CameraScanSupport.kt"
  "$SRC/MapLocatorTypes.kt"
  "$SRC/MotionTracker.kt"
  "$SRC/MapLocatorPure.kt"
  "$SRC/MatchValidation.kt"
  "$SRC/YoloMapping.kt"
  "$SRC/CameraOrientationDecode.kt"
  "$SRC/MapLocateActionPure.kt"
  "$SRC/MapLocatorProbeSupport.kt"
  "$SRC/YoloPreprocess.kt"
  "$SRC/YoloClassifySupport.kt"
  "$SRC/MapLocatorCoarsePure.kt"
  "$SRC/MapLocatorCalibration.kt"
  "$SRC/MapLocatorRefinePure.kt"
  "$SRC/MapLocatorPathHeatmap.kt"
  "$SRC/MapLocatorHeatmapPipeline.kt"
  "$SRC/MapLocatorTracking.kt"
  "$SRC/MapLocateAssertPure.kt"
  "$SRC/WorldMapTypes.kt"
  "$SRC/WorldMapFindPure.kt"
  "$SRC/WorldMapSolverPure.kt"
  "$SRC/WorldMapImagePure.kt"
  "$SUP/SupplementPack.kt"
  "$SUP/SupplementPackLocal.kt"
  "$DIAG/RunDiagnosticsPolicy.kt"
  "$DIAG/GoodsProbeDumpPolicy.kt"
    "$CLI/DebugCliSupport.kt"
    "$CLI/DebugCliRemote.kt"
    "$CLI/DebugCliRelay.kt"
    "$PRIV/OpenCvEmulatorCompat.kt"
  )
TEST_FILES=(
  "$TST/BetterSlidingSupportTest.kt"
  "$TST/BetterSlidingParamsTest.kt"
  "$TST/BetterSlidingDecisionTest.kt"
  "$TST/BetterSlidingOverridesTest.kt"
  "$TST/BetterSlidingOcrTest.kt"
  "$TST/BetterSlidingSessionTest.kt"
  "$TST/JsonTreeTest.kt"
  "$TST/OcrProbeSupportTest.kt"
  "$TST/AutoStockpileSupportTest.kt"
  "$TST/AutoStockStapleSupportTest.kt"
  "$TST/OutpostReserveSupportTest.kt"
  "$TST/OutpostPrioritySupportTest.kt"
  "$TST/ScheduleSupportTest.kt"
  "$TST/OperatorOcrMatchTest.kt"
  "$TST/OperatorDatasetTest.kt"
  "$TST/OperatorSelectionTest.kt"
  "$TST/OperatorCacheTest.kt"
  "$TST/OperatorSessionTest.kt"
  "$TST/OperatorScanTest.kt"
  "$TST/OperatorRecognitionsTest.kt"
  "$TST/OperatorRuntimeTest.kt"
  "$TST/MapNaviParamTest.kt"
  "$TST/MapNavHeadingTest.kt"
  "$TST/MapNavControlPureTest.kt"
  "$TST/MapNavWalkPureTest.kt"
  "$TST/PipelineOverrideSupportTest.kt"
  "$TST/ReceptionRoomSupportTest.kt"
  "$TST/ItemTransferSupportTest.kt"
  "$TST/IntelArchiveSupportTest.kt"
  "$TST/ListCompleteSupportTest.kt"
  "$TST/FailureCollectorSupportTest.kt"
  "$TST/BoolExprTest.kt"
  "$TST/ItemQuantitySupportTest.kt"
  "$TST/AutoEcoFarmSwipeTest.kt"
  "$TST/AutoEcoFarmNearestTest.kt"
  "$TST/AutoEcoFarmOverrideTest.kt"
  "$TST/AutoEcoFarmSleepTest.kt"
  "$TST/OngoingDeliverySupportTest.kt"
  "$TST/SeizeDeliverySupportTest.kt"
  "$TST/CameraScanSupportTest.kt"
  "$TST/MapLocatorTypesTest.kt"
  "$TST/MotionTrackerPureTest.kt"
  "$TST/MapLocatorPureTest.kt"
  "$TST/MatchValidationTest.kt"
  "$TST/YoloMappingTest.kt"
  "$TST/CameraOrientationDecodeTest.kt"
  "$TST/MapLocateActionPureTest.kt"
  "$TST/MapLocatorProbeSupportTest.kt"
  "$TST/YoloPreprocessTest.kt"
  "$TST/YoloClassifySupportTest.kt"
  "$TST/MapLocatorCoarsePureTest.kt"
  "$TST/MapLocatorCalibrationTest.kt"
  "$TST/MapLocatorRefinePureTest.kt"
  "$TST/MapLocatorPathHeatmapTest.kt"
  "$TST/MapLocatorHeatmapPipelineTest.kt"
  "$TST/MapLocatorTrackingTest.kt"
  "$TST/MapLocateAssertPureTest.kt"
  "$TST/WorldMapTypesTest.kt"
  "$TST/WorldMapFindPureTest.kt"
  "$TST/WorldMapSolverPureTest.kt"
  "$TST/WorldMapImagePureTest.kt"
  "$TSUP/SupplementPackTest.kt"
  "$TSUP/SupplementPackLocalTest.kt"
  "$TDIAG/RunDiagnosticsPolicyTest.kt"
  "$TDIAG/GoodsProbeDumpPolicyTest.kt"
    "$TCLI/DebugCliSupportTest.kt"
    "$TCLI/DebugCliRemoteTest.kt"
    "$TCLI/DebugCliRelayTest.kt"
    "$TPRIV/OpenCvEmulatorCompatTest.kt"
  )
TEST_CLASSES=(
  com.aliothmoon.maafw.remote.BetterSlidingSupportTest
  com.aliothmoon.maafw.remote.BetterSlidingParamsTest
  com.aliothmoon.maafw.remote.BetterSlidingDecisionTest
  com.aliothmoon.maafw.remote.BetterSlidingOverridesTest
  com.aliothmoon.maafw.remote.BetterSlidingOcrTest
  com.aliothmoon.maafw.remote.BetterSlidingSessionTest
  com.aliothmoon.maafw.remote.JsonTreeTest
  com.aliothmoon.maafw.remote.OcrProbeSupportTest
  com.aliothmoon.maafw.remote.AutoStockpileSupportTest
  com.aliothmoon.maafw.remote.AutoStockStapleSupportTest
  com.aliothmoon.maafw.remote.OutpostReserveSupportTest
  com.aliothmoon.maafw.remote.OutpostPrioritySupportTest
  com.aliothmoon.maafw.remote.ScheduleSupportTest
  com.aliothmoon.maafw.remote.OperatorOcrMatchTest
  com.aliothmoon.maafw.remote.OperatorDatasetTest
  com.aliothmoon.maafw.remote.OperatorSelectionTest
  com.aliothmoon.maafw.remote.OperatorCacheTest
  com.aliothmoon.maafw.remote.OperatorSessionTest
  com.aliothmoon.maafw.remote.OperatorScanTest
  com.aliothmoon.maafw.remote.OperatorRecognitionsTest
  com.aliothmoon.maafw.remote.OperatorRuntimeTest
  com.aliothmoon.maafw.remote.MapNaviParamTest
  com.aliothmoon.maafw.remote.MapNavHeadingTest
  com.aliothmoon.maafw.remote.MapNavControlPureTest
  com.aliothmoon.maafw.remote.MapNavWalkPureTest
  com.aliothmoon.maafw.remote.PipelineOverrideSupportTest
  com.aliothmoon.maafw.remote.ReceptionRoomSupportTest
  com.aliothmoon.maafw.remote.ItemTransferSupportTest
  com.aliothmoon.maafw.remote.IntelArchiveSupportTest
  com.aliothmoon.maafw.remote.ListCompleteSupportTest
  com.aliothmoon.maafw.remote.FailureCollectorSupportTest
  com.aliothmoon.maafw.remote.BoolExprTest
  com.aliothmoon.maafw.remote.ItemQuantitySupportTest
  com.aliothmoon.maafw.remote.AutoEcoFarmSwipeTest
  com.aliothmoon.maafw.remote.AutoEcoFarmNearestTest
  com.aliothmoon.maafw.remote.AutoEcoFarmOverrideTest
  com.aliothmoon.maafw.remote.AutoEcoFarmSleepTest
  com.aliothmoon.maafw.remote.OngoingDeliverySupportTest
  com.aliothmoon.maafw.remote.SeizeDeliverySupportTest
  com.aliothmoon.maafw.remote.CameraScanSupportTest
  com.aliothmoon.maafw.remote.MapLocatorTypesTest
  com.aliothmoon.maafw.remote.MotionTrackerPureTest
  com.aliothmoon.maafw.remote.MapLocatorPureTest
  com.aliothmoon.maafw.remote.MatchValidationTest
  com.aliothmoon.maafw.remote.YoloMappingTest
  com.aliothmoon.maafw.remote.CameraOrientationDecodeTest
  com.aliothmoon.maafw.remote.MapLocateActionPureTest
  com.aliothmoon.maafw.remote.MapLocatorProbeSupportTest
  com.aliothmoon.maafw.remote.YoloPreprocessTest
  com.aliothmoon.maafw.remote.YoloClassifySupportTest
  com.aliothmoon.maafw.remote.MapLocatorCoarsePureTest
  com.aliothmoon.maafw.remote.MapLocatorCalibrationTest
  com.aliothmoon.maafw.remote.MapLocatorRefinePureTest
  com.aliothmoon.maafw.remote.MapLocatorPathHeatmapTest
  com.aliothmoon.maafw.remote.MapLocatorHeatmapPipelineTest
  com.aliothmoon.maafw.remote.MapLocatorTrackingTest
  com.aliothmoon.maafw.remote.MapLocateAssertPureTest
  com.aliothmoon.maafw.remote.WorldMapTypesTest
  com.aliothmoon.maafw.remote.WorldMapFindPureTest
  com.aliothmoon.maafw.remote.WorldMapSolverPureTest
  com.aliothmoon.maafw.remote.WorldMapImagePureTest
  com.aliothmoon.maafw.supplement.SupplementPackTest
  com.aliothmoon.maafw.supplement.SupplementPackLocalTest
  com.aliothmoon.maafw.diagnostics.RunDiagnosticsPolicyTest
  com.aliothmoon.maafw.diagnostics.GoodsProbeDumpPolicyTest
    com.aliothmoon.maafw.cli.DebugCliSupportTest
    com.aliothmoon.maafw.cli.DebugCliRemoteTest
    com.aliothmoon.maafw.cli.DebugCliRelayTest
    com.aliothmoon.maafw.privileged.OpenCvEmulatorCompatTest
  )

stubs() {
  mkdir -p "$STUB/org/junit/function" "$WORK/jstubout"
  cat > "$STUB/org/junit/Test.java" <<'J'
package org.junit;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface Test {}
J
  for ann in Before After BeforeClass AfterClass; do
    cat > "$STUB/org/junit/$ann.java" <<J
package org.junit;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface $ann {}
J
  done
  cat > "$STUB/org/junit/function/ThrowingRunnable.java" <<'J'
package org.junit.function;
public interface ThrowingRunnable { void run() throws Throwable; }
J
  cat > "$STUB/org/junit/Assert.java" <<'J'
package org.junit;
public class Assert {
  // 这些桩必须**真的**断言：空实现会让所有用例无条件通过，
  // 那样"全绿"就是自欺欺人（曾经踩过）。
  public static void assertEquals(Object expected, Object actual) {
    boolean ok = (expected == null) ? (actual == null) : expected.equals(actual);
    if (!ok) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
  }
  public static void assertEquals(long expected, long actual) {
    if (expected != actual) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
  }
  public static void assertEquals(double expected, double actual, double delta) {
    if (Math.abs(expected - actual) > delta)
      throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
  }
  public static void assertEquals(String m, double expected, double actual, double delta) {
    if (Math.abs(expected - actual) > delta)
      throw new AssertionError(m + ": expected <" + expected + "> but was <" + actual + ">");
  }
  public static void assertEquals(String m, Object expected, Object actual) {
    boolean ok = (expected == null) ? (actual == null) : expected.equals(actual);
    if (!ok) throw new AssertionError(m + ": expected <" + expected + "> but was <" + actual + ">");
  }
  public static void assertEquals(String m, long expected, long actual) {
    if (expected != actual) throw new AssertionError(m + ": expected <" + expected + "> but was <" + actual + ">");
  }
  public static void assertNotEquals(Object a, Object b) {
    boolean same = (a == null) ? (b == null) : a.equals(b);
    if (same) throw new AssertionError("expected not equal to <" + a + ">");
  }
  public static void assertTrue(boolean c) { if (!c) throw new AssertionError("expected true"); }
  public static void assertTrue(String m, boolean c) { if (!c) throw new AssertionError(m); }
  public static void assertFalse(boolean c) { if (c) throw new AssertionError("expected false"); }
  public static void assertFalse(String m, boolean c) { if (c) throw new AssertionError(m); }
  public static void assertNull(Object a) { if (a != null) throw new AssertionError("expected null but was <" + a + ">"); }
  public static void assertNull(String m, Object a) { if (a != null) throw new AssertionError(m); }
  public static void assertNotNull(Object a) { if (a == null) throw new AssertionError("expected not null"); }
  public static void assertNotNull(String m, Object a) { if (a == null) throw new AssertionError(m); }
  public static void fail(String m) { throw new AssertionError(m); }
  public static <T extends Throwable> T assertThrows(Class<T> c, org.junit.function.ThrowingRunnable r) {
    try {
      r.run();
    } catch (Throwable actual) {
      if (c.isInstance(actual)) return c.cast(actual);
      throw new AssertionError("expected " + c.getName() + " but got " + actual);
    }
    throw new AssertionError("expected " + c.getName() + " but nothing was thrown");
  }
}
J
  javac -d "$WORK/jstubout" "$STUB"/org/junit/*.java "$STUB"/org/junit/function/*.java
  echo "JUnit stubs -> $WORK/jstubout"
}

main() {
  rm -rf "$WORK/out" && mkdir -p "$WORK/out"
  if java -cp "$JARS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib \
      -cp "$COMPILE_CP" -d "$WORK/out" "${MAIN_FILES[@]}" 2>&1 | grep -E "error:"; then
    echo "main: COMPILE FAILED"; exit 1
  fi
  echo "main compiled -> $WORK/out"
}

typecheck() {
  [[ -d "$WORK/jstubout" ]] || stubs
  [[ -d "$WORK/out" ]] || main
  rm -rf "$WORK/testout" && mkdir -p "$WORK/testout"
  if java -cp "$JARS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib \
      -cp "$COMPILE_CP:$WORK/jstubout:$WORK/out" -d "$WORK/testout" "${TEST_FILES[@]}" 2>&1 | grep -E "error:"; then
    echo "tests: TYPECHECK FAILED"; exit 1
  fi
  echo "tests typecheck ok"
}

runner() {
  cat > "$WORK/Runner.kt" <<'K'
fun main() {
    val classes = listOf(
        "com.aliothmoon.maafw.remote.BetterSlidingSupportTest",
        "com.aliothmoon.maafw.remote.BetterSlidingParamsTest",
        "com.aliothmoon.maafw.remote.BetterSlidingDecisionTest",
        "com.aliothmoon.maafw.remote.BetterSlidingOverridesTest",
        "com.aliothmoon.maafw.remote.BetterSlidingOcrTest",
        "com.aliothmoon.maafw.remote.BetterSlidingSessionTest",
        "com.aliothmoon.maafw.remote.JsonTreeTest",
        "com.aliothmoon.maafw.remote.OcrProbeSupportTest",
        "com.aliothmoon.maafw.remote.AutoStockpileSupportTest",
        "com.aliothmoon.maafw.remote.AutoStockStapleSupportTest",
        "com.aliothmoon.maafw.remote.OutpostReserveSupportTest",
        "com.aliothmoon.maafw.remote.OutpostPrioritySupportTest",
        "com.aliothmoon.maafw.remote.ScheduleSupportTest",
        "com.aliothmoon.maafw.remote.OperatorOcrMatchTest",
        "com.aliothmoon.maafw.remote.OperatorDatasetTest",
        "com.aliothmoon.maafw.remote.OperatorSelectionTest",
        "com.aliothmoon.maafw.remote.OperatorCacheTest",
        "com.aliothmoon.maafw.remote.OperatorSessionTest",
        "com.aliothmoon.maafw.remote.OperatorScanTest",
        "com.aliothmoon.maafw.remote.OperatorRecognitionsTest",
        "com.aliothmoon.maafw.remote.OperatorRuntimeTest",
        "com.aliothmoon.maafw.remote.MapNaviParamTest",
        "com.aliothmoon.maafw.remote.MapNavHeadingTest",
        "com.aliothmoon.maafw.remote.MapNavControlPureTest",
        "com.aliothmoon.maafw.remote.MapNavWalkPureTest",
        "com.aliothmoon.maafw.remote.PipelineOverrideSupportTest",
        "com.aliothmoon.maafw.remote.ReceptionRoomSupportTest",
        "com.aliothmoon.maafw.remote.ItemTransferSupportTest",
        "com.aliothmoon.maafw.remote.IntelArchiveSupportTest",
        "com.aliothmoon.maafw.remote.ListCompleteSupportTest",
        "com.aliothmoon.maafw.remote.FailureCollectorSupportTest",
        "com.aliothmoon.maafw.remote.BoolExprTest",
        "com.aliothmoon.maafw.remote.ItemQuantitySupportTest",
        "com.aliothmoon.maafw.remote.AutoEcoFarmSwipeTest",
        "com.aliothmoon.maafw.remote.AutoEcoFarmNearestTest",
        "com.aliothmoon.maafw.remote.AutoEcoFarmOverrideTest",
        "com.aliothmoon.maafw.remote.AutoEcoFarmSleepTest",
        "com.aliothmoon.maafw.remote.OngoingDeliverySupportTest",
        "com.aliothmoon.maafw.remote.SeizeDeliverySupportTest",
        "com.aliothmoon.maafw.remote.CameraScanSupportTest",
        "com.aliothmoon.maafw.remote.MapLocatorTypesTest",
        "com.aliothmoon.maafw.remote.MotionTrackerPureTest",
        "com.aliothmoon.maafw.remote.MapLocatorPureTest",
        "com.aliothmoon.maafw.remote.MatchValidationTest",
        "com.aliothmoon.maafw.remote.YoloMappingTest",
        "com.aliothmoon.maafw.remote.CameraOrientationDecodeTest",
        "com.aliothmoon.maafw.remote.MapLocateActionPureTest",
        "com.aliothmoon.maafw.remote.MapLocatorProbeSupportTest",
        "com.aliothmoon.maafw.remote.YoloPreprocessTest",
        "com.aliothmoon.maafw.remote.YoloClassifySupportTest",
        "com.aliothmoon.maafw.remote.MapLocatorCoarsePureTest",
        "com.aliothmoon.maafw.remote.MapLocatorCalibrationTest",
        "com.aliothmoon.maafw.remote.MapLocatorRefinePureTest",
        "com.aliothmoon.maafw.remote.MapLocatorPathHeatmapTest",
        "com.aliothmoon.maafw.remote.MapLocatorHeatmapPipelineTest",
        "com.aliothmoon.maafw.remote.MapLocatorTrackingTest",
        "com.aliothmoon.maafw.remote.MapLocateAssertPureTest",
        "com.aliothmoon.maafw.remote.WorldMapTypesTest",
        "com.aliothmoon.maafw.remote.WorldMapFindPureTest",
        "com.aliothmoon.maafw.remote.WorldMapSolverPureTest",
        "com.aliothmoon.maafw.remote.WorldMapImagePureTest",
        "com.aliothmoon.maafw.supplement.SupplementPackTest",
        "com.aliothmoon.maafw.supplement.SupplementPackLocalTest",
        "com.aliothmoon.maafw.diagnostics.RunDiagnosticsPolicyTest",
        "com.aliothmoon.maafw.diagnostics.GoodsProbeDumpPolicyTest",
        "com.aliothmoon.maafw.cli.DebugCliSupportTest",
        "com.aliothmoon.maafw.cli.DebugCliRemoteTest",
        "com.aliothmoon.maafw.cli.DebugCliRelayTest",
        "com.aliothmoon.maafw.privileged.OpenCvEmulatorCompatTest",
    )
    var pass = 0
    var fail = 0
    for (name in classes) {
        val cls = Class.forName(name)
        println("--- $name")
        for (m in cls.declaredMethods) {
            if (m.getAnnotation(org.junit.Test::class.java) == null) continue
            val instance = cls.getDeclaredConstructor().newInstance()
            try {
                // JUnit 语义：每个 @Test 前先跑 @Before（AutoStockpileSupportTest 靠它 reset Session）
                for (b in cls.declaredMethods) {
                    if (b.getAnnotation(org.junit.Before::class.java) != null) b.invoke(instance)
                }
                m.invoke(instance)
                pass++
                println("  ok   ${m.name}")
            } catch (e: java.lang.reflect.InvocationTargetException) {
                fail++
                println("  FAIL ${m.name}: ${e.targetException}")
            }
        }
    }
    println()
    println("tests run: ${pass + fail}, passed: $pass, failed: $fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}
K
  rm -rf "$WORK/runnerout" && mkdir -p "$WORK/runnerout"
  if java -cp "$JARS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib \
    -cp "$COMPILE_CP:$WORK/jstubout:$WORK/out" -d "$WORK/runnerout" "$WORK/Runner.kt" 2>&1 | grep -E "error:"; then
    echo "runner: COMPILE FAILED"; exit 1
  fi
  echo "runner compiled"
}

run() {
  [[ -d "$WORK/testout" ]] || typecheck
  [[ -f "$WORK/runnerout/RunnerKt.class" ]] || runner
  java -cp "$COMPILE_CP:$WORK/jstubout:$WORK/out:$WORK/testout:$WORK/runnerout" RunnerKt
}

case "$1" in
  stubs) stubs;;
  main) main;;
  typecheck) typecheck;;
  runner) runner;;
  run) run;;
  all) stubs; main; typecheck; runner; run; echo; echo "ALL OK";;
  *) grep '^#' "$0" | sed 's/^# \{0,1\}//';;
esac
