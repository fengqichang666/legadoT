package io.legado.app.base

import android.content.DialogInterface
import android.content.DialogInterface.OnDismissListener
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.skin.SkinInflaterFactory
import io.legado.app.lib.theme.AppColorScheme
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setBackgroundKeepPadding
import io.legado.app.utils.setRoundBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext


abstract class BaseDialogFragment(
    @LayoutRes layoutID: Int,
    private val adaptationSoftKeyboard: Boolean = false
) : DialogFragment(layoutID) {

    /**
     * 弹窗渲染形态:只管"模板怎么画",窗口尺寸由子类 [setLayout] 决定,
     * 是否四周留边由 [dialogEdgeInset] 决定,三者正交.
     */
    enum class DialogForm {
        /** 浮动弹窗:surfaceContainerHigh 容器+radius_xl 圆角+透明窗,与 AlertDialog/WaitDialog 同语言 */
        FLOATING,

        /** 自管背景:贴底面板/全屏遮罩/图片查看器等自设背景与 gravity,模板不介入 */
        SELF_MANAGED,
    }

    protected open val dialogForm: DialogForm = DialogForm.FLOATING

    /**
     * 四周留边(大浮动卡片):在 [onStart] 给 decorView 加 [EDGE_INSET_DP] padding,
     * 留边区在窗口内、露出遮罩,点按不关闭.
     *
     * 与 [dialogForm] 正交:尺寸仍由子类 setLayout 决定(WRAP_CONTENT 时内容少就矮),
     * 需要"铺满宽 + 高度自适应 + 有留边"的弹窗(如各导入弹窗)用它而不是改窗口尺寸.
     */
    protected open val dialogEdgeInset: Boolean = false

    private var onDismissListener: OnDismissListener? = null

    fun setOnDismissListener(onDismissListener: OnDismissListener?) {
        this.onDismissListener = onDismissListener
    }

    override fun onStart() {
        super.onStart()
        if (!adaptationSoftKeyboard && !AppConfig.isEInkMode && dialogEdgeInset) {
            // 四周留边:窗口保持子类 setLayout 的全尺寸(其只改宽高,不碰 padding),
            // 留边区在窗口内、露出遮罩;点按不关闭与旧版系统 inset 行为一致
            val inset = EDGE_INSET_DP.dpToPx()
            dialog?.window?.decorView?.setPadding(inset, inset, inset, inset)
        }
        if (adaptationSoftKeyboard) {
            dialog?.window?.setBackgroundDrawableResource(R.color.transparent)
        } else if (AppConfig.isEInkMode) {
            dialog?.window?.let {
                it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                val attr = it.attributes
                attr.dimAmount = 0.0f
                attr.windowAnimations = 0
                it.attributes = attr
                it.decorView.setBackgroundKeepPadding(R.color.transparent)
            }
            // 修改gravity的时机一般在子类的onStart方法中, 因此需要在onStart之后执行.
            lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    when (dialog?.window?.attributes?.gravity) {
                        Gravity.TOP -> view?.setBackgroundResource(R.drawable.bg_eink_border_bottom)
                        Gravity.BOTTOM -> view?.setBackgroundResource(R.drawable.bg_eink_border_top)
                        else -> {
                            val padding = 2.dpToPx();
                            view?.setPadding(padding, padding, padding, padding)
                            view?.setBackgroundResource(R.drawable.bg_eink_border_dialog)
                        }
                    }
                }
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            //不加这个android 5.0对话框顶部会有空白
            setStyle(STYLE_NO_TITLE, 0)
        }
    }

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        // R2 换肤引擎弹窗通路:Dialog 窗口 inflater 通常由 activity inflater 克隆而来,
        // 工厂已随克隆继承(installOn 空转);OEM 魔改返回全新 inflater 时在此兜底补装
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        (activity as? AppCompatActivity)?.let { SkinInflaterFactory.installOn(inflater, it) }
        return inflater
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (adaptationSoftKeyboard) {
            view.findViewById<View>(R.id.vw_bg)?.setOnClickListener(null)
            view.setOnClickListener { dismiss() }
        } else if (!AppConfig.isEInkMode) {
            view.setBackgroundColor(backgroundColor)
        }
        onFragmentCreated(view, savedInstanceState)
        observeLiveBus()
        // 模板在子类 onFragmentCreated 之后执行，覆盖其 setBackgroundColor
        if (!AppConfig.isEInkMode && dialogForm == DialogForm.FLOATING) {
            applyDialogTemplate(view)
        } else if (adaptationSoftKeyboard) {
            // adaptation 形态不走 onStart 的 eink 分支,中央卡片在此补 eink 边框
            view.findViewById<View>(R.id.vw_bg)
                ?.setBackgroundResource(R.drawable.bg_eink_border_dialog)
        }
    }

    /**
     * 浮动形态:根布局整卡圆角+裁剪、窗口透明（否则圆角缺口露出窗口方角背景）;
     * adaptation 形态（透明窗+点外关闭）:只染中央卡片（即吃掉点击的 R.id.vw_bg）。
     * Toolbar 顶部圆角与容器同半径;子类未设色时兜底主色。
     * 窗口尺寸与四周留边均不在此处理——前者走子类 setLayout,后者走 [dialogEdgeInset]。
     */
    private fun applyDialogTemplate(view: View) {
        val radius = resources.getDimension(R.dimen.radius_xl)
        val roundTarget = if (adaptationSoftKeyboard) {
            view.findViewById<View>(R.id.vw_bg) ?: return
        } else {
            dialog?.window?.setBackgroundDrawableResource(R.color.transparent)
            view
        }
        roundTarget.setRoundBackground(AppColorScheme.current.surfaceContainerHigh, radius = radius)
        roundTarget.clipToOutline = true
        view.findViewById<View>(R.id.tool_bar)?.let { toolBar ->
            val color = (toolBar.background as? ColorDrawable)?.color ?: primaryColor
            toolBar.setRoundBackground(color, topOnly = true, radius = radius)
            toolBar.elevation = 0f
        }
    }

    abstract fun onFragmentCreated(view: View, savedInstanceState: Bundle?)

    override fun show(manager: FragmentManager, tag: String?) {
        kotlin.runCatching {
            //在每个add事务前增加一个remove事务，防止连续的add
            manager.beginTransaction().remove(this).commit()
            super.show(manager, tag)
        }.onFailure {
            AppLog.put("显示对话框失败 tag:$tag", it)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
    }

    fun <T> execute(
        scope: CoroutineScope = lifecycleScope,
        context: CoroutineContext = Dispatchers.IO,
        block: suspend CoroutineScope.() -> T
    ) = Coroutine.async(scope, context) { block() }

    open fun observeLiveBus() {
    }

    companion object {
        /** [dialogEdgeInset] 的留边宽度 */
        private const val EDGE_INSET_DP = 16
    }
}