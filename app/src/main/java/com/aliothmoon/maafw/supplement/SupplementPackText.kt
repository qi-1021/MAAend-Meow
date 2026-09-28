package com.aliothmoon.maafw.supplement

import androidx.annotation.StringRes
import com.aliothmoon.maafw.R

/**
 * 补充包的**包 id → 文案资源**映射。
 *
 * 为什么不用 `resources.getIdentifier(pack.nameKey, ...)` 动态查：
 * release 构建会跑资源压缩，R8 看不到静态引用就会把 `supplement_pack_*` 这批
 * 只被动态引用的字符串裁掉——编译期毫无提示，装到机器上才变成「包名显示成
 * `map-locate`」这种哑巴亏。这里写成 `when`，编译期可查、压缩器看得见。
 *
 * 代价是新增包要在这里补一行；包是固定的三个，这个代价换来的是不会静默失效。
 * 清单里的 `nameKey`/`summaryKey` 保留为可读的对应说明，不再参与运行期查找。
 */
object SupplementPackText {

    @StringRes
    fun nameRes(packId: String): Int? = when (packId) {
        "map-locate" -> R.string.supplement_pack_map_locate_name
        "map-navmesh" -> R.string.supplement_pack_map_navmesh_name
        "detect" -> R.string.supplement_pack_detect_name
        else -> null
    }

    @StringRes
    fun summaryRes(packId: String): Int? = when (packId) {
        "map-locate" -> R.string.supplement_pack_map_locate_summary
        "map-navmesh" -> R.string.supplement_pack_map_navmesh_summary
        "detect" -> R.string.supplement_pack_detect_summary
        else -> null
    }
}
