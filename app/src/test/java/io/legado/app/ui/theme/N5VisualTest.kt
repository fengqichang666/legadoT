package io.legado.app.ui.theme

import io.legado.app.ProjectSources
import org.junit.Assert.assertTrue
import org.junit.Test

/** N5 Wave B 视觉收尾哨兵 */
class N5VisualTest {
    @Test
    fun `app slider style tames thumb and gap`() {
        val styles = ProjectSources.app("src/main/res/values/styles.xml")
        assertTrue("应定义 Widget.App.Slider", styles.contains("name=\"Widget.App.Slider\""))
        assertTrue("应去缺口 thumbTrackGapSize=0", styles.contains("thumbTrackGapSize"))
        assertTrue("应去尾端停止点 trackStopIndicatorSize", styles.contains("trackStopIndicatorSize"))
        assertTrue("Base.AppTheme 应挂 sliderStyle", styles.contains("name=\"sliderStyle\""))
    }

    @Test
    fun `bookmark item is carded`() {
        val xml = ProjectSources.app("src/main/res/layout/item_bookmark.xml")
        assertTrue("书签 item 应套卡片", xml.contains("Style.ItemManageCard"))
        assertTrue("卡容器应声明 surfaceContainerLow", xml.contains("surfaceContainerLow"))
        assertTrue("id 保全:tv_chapter_name", xml.contains("@+id/tv_chapter_name"))
        assertTrue("id 保全:tv_content", xml.contains("@+id/tv_content"))
    }

    @Test
    fun `search content item is carded`() {
        val xml = ProjectSources.app("src/main/res/layout/item_search_list.xml")
        assertTrue("书内搜索 item 应套卡片", xml.contains("Style.ItemManageCard"))
        assertTrue("id 保全:tv_search_result", xml.contains("@+id/tv_search_result"))
        assertTrue("修正约束笔误", xml.contains("constraintRight_toRightOf"))
    }

    @Test
    fun `font item selection has stroke highlight`() {
        val xml = ProjectSources.app("src/main/res/layout/item_font.xml")
        assertTrue("字体 item root 应为 MaterialCardView 承接描边", xml.contains("MaterialCardView"))
        val adapter = ProjectSources.app("src/main/java/io/legado/app/ui/font/FontAdapter.kt")
        assertTrue("选中态应设描边", adapter.contains("strokeWidth") || adapter.contains("setStrokeColor"))
    }

    @Test
    fun `holo selector purged from debug and page key`() {
        val pk = ProjectSources.app("src/main/res/layout/dialog_page_key.xml")
        val dbg = ProjectSources.app("src/main/res/layout/activity_source_debug.xml")
        assertTrue("dialog_page_key 不应再用 Holo selector", !pk.contains("selector_fillet_btn_bg"))
        assertTrue("source_debug 不应再用 Holo selector", !dbg.contains("selector_fillet_btn_bg"))
        assertTrue("应改用 ripple 药丸", pk.contains("bg_fillet_btn"))
    }

    @Test
    fun `rss source edit top matches book source card chip`() {
        val xml = ProjectSources.app("src/main/res/layout/activity_rss_source_edit.xml")
        assertTrue("RSS 顶部应卡片化", xml.contains("MaterialCardView"))
        assertTrue("RSS 开关应 Chip 化", xml.contains("Widget.App.Chip"))
        assertTrue("应有折叠头摘要", xml.contains("@+id/tv_top_summary"))
        assertTrue("id 保全:cb_is_enable", xml.contains("@+id/cb_is_enable"))
        assertTrue("id 保全:cb_single_url", xml.contains("@+id/cb_single_url"))
    }

    @Test
    fun `book source summary card floats and import has empty icon`() {
        val edit = ProjectSources.app("src/main/res/layout/activity_book_source_edit.xml")
        assertTrue("摘要卡应用 background_card 浮起", edit.contains("cardBackgroundColor=\"@color/background_card\""))
        val dlg = ProjectSources.app("src/main/res/layout/dialog_recycler_view.xml")
        assertTrue("导入弹窗空态应有图标", dlg.contains("@+id/iv_empty"))
    }

    @Test
    fun `adaptive import dialogs avoid fill-height measurement layers`() {
        val layout = ProjectSources.app("src/main/res/layout/dialog_recycler_view_adaptive.xml")
        assertTrue("导入布局应由父容器统一分配列表和 footer 高度", layout.contains("ContentSizedDialogLayout"))
        assertTrue("导入列表应使用受限 RecyclerView", layout.contains("ContentSizedRecyclerView"))
        assertTrue("导入列表应保留空态图标", layout.contains("@+id/iv_empty"))
        assertTrue("导入列表不能嵌套下拉刷新测量层", !layout.contains("SwipeRefreshLayout"))
        assertTrue("导入列表不能动态叠加 fast scroller", !layout.contains("FastScrollRecyclerView"))
        assertTrue("导入列表不能用权重分配可用高度", !layout.contains("layout_weight"))

        val imports = listOf(
            "ui/association/ImportBookSourceDialog",
            "ui/association/ImportRssSourceDialog",
            "ui/association/ImportReplaceRuleDialog",
            "ui/association/ImportTxtTocRuleDialog",
            "ui/association/ImportDictRuleDialog",
            "ui/association/ImportHttpTtsDialog",
            "ui/association/ImportThemeDialog",
            "ui/autoTask/ImportAutoTaskDialog",
        )
        imports.forEach {
            val source = ProjectSources.app("src/main/java/io/legado/app/$it.kt")
            assertTrue("$it 不应再使用共享填充布局", !source.contains("R.layout.dialog_recycler_view)"))
            assertTrue("$it 不应再绑定共享刷新布局", !source.contains("DialogRecyclerViewBinding"))
        }
    }

    /**
     * 尺寸归 setLayout、留边归 dialogEdgeInset 的正交契约。
     *
     * 只断言「禁止出现」:早期实现把留边耦合在 DialogForm.FULL_SCREEN 里,
     * 导致 WRAP_CONTENT 高度的导入弹窗被当成全屏弹窗处理。
     * 新机制(dialogEdgeInset)的存在性由编译器保证(子类 override 即绑定),
     * 尺寸策略(WRAP_CONTENT/MATCH_PARENT)是设计选择而非不变量,哨兵不冻结。
     */
    @Test
    fun `import dialogs no longer couple inset to form`() {
        val families = listOf(
            "ui/association/ImportBookSourceDialog",
            "ui/association/ImportRssSourceDialog",
            "ui/association/ImportReplaceRuleDialog",
            "ui/association/ImportTxtTocRuleDialog",
            "ui/association/ImportDictRuleDialog",
            "ui/association/ImportHttpTtsDialog",
            "ui/association/ImportThemeDialog",
            "ui/autoTask/ImportAutoTaskDialog",
            "ui/association/VerificationCodeDialog",
            "ui/association/OpenUrlConfirmDialog",
        )
        families.forEach {
            val src = ProjectSources.app("src/main/java/io/legado/app/$it.kt")
            assertTrue("$it 不应再用 DialogForm 表达留边", !src.contains("DialogForm.FULL_SCREEN"))
        }
    }

    /** 跨文件关系:留边触发条件与 dialogForm 无关,模板分支不再区分画法相同的两支。 */
    @Test
    fun `base dialog keeps form and inset orthogonal`() {
        val base = ProjectSources.app("src/main/java/io/legado/app/base/BaseDialogFragment.kt")
        assertTrue("DialogForm 不应再含 FULL_SCREEN", !base.contains("FULL_SCREEN"))
        assertTrue("留边不应由 dialogForm 触发", !base.contains("dialogForm == DialogForm.FULL_SCREEN"))
    }

    @Test
    fun `font sizes bound to tokens except reader canvas`() {
        val samples = listOf("dialog_sleep_timer", "activity_source_debug", "item_manage")
        samples.forEach {
            val xml = ProjectSources.app("src/main/res/layout/$it.xml")
            val active = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            assertTrue("$it 应无 1[2-8]sp 字面量",
                !active.contains(Regex("textSize=\"1[2-8]sp\"")))
        }
        // 红线:阅读器画布不动
        val page = ProjectSources.app("src/main/res/layout/view_book_page.xml")
        assertTrue("view_book_page 保留自绘字号字面量", page.contains(Regex("textSize=\"\\d+sp\"")))
    }
}
