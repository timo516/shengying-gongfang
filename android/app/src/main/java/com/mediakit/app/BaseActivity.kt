package com.mediakit.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 两个界面共用的"代码拼 UI"基建。
 *
 * 这些 helper 以前在两个 Activity 里各写了一遍（约 80 行重复），
 * 改一处忘另一处是这类项目最常见的 bug 来源，所以统一放这儿。
 */
abstract class BaseActivity : Activity() {

    /** 需要跟着 busy 状态一起禁用/启用的按钮 */
    protected val actionButtons = ArrayList<Button>()

    /** 编辑器里按钮多、要两列排，字号比主页小一号 */
    protected open val buttonTextSize: Float = 14f

    protected fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    protected fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    protected fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        .apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        }

    protected fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(getColor(R.color.accent))
        setPadding(0, dp(6), 0, 0)
    }

    protected fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(getColor(R.color.text_hint))
        setPadding(dp(2), dp(8), dp(2), 0)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    protected fun actionButton(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = buttonTextSize
        b.setPadding(dp(6), 0, dp(6), 0)
        b.minWidth = 0
        b.minimumWidth = 0
        b.setOnClickListener { onClick() }
        actionButtons.add(b)
        return b
    }

    protected fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    protected fun setActionsEnabled(enabled: Boolean) {
        for (b in actionButtons) b.isEnabled = enabled
    }

    /**
     * 任务是在裸线程里跑的，跑完时页面可能已经被用户按返回关掉了。
     * 这时候再去动 Dialog/视图就是 BadTokenException 崩溃，所以先问一句。
     */
    protected fun uiAlive(): Boolean = !isFinishing && !isDestroyed

    protected fun displayName(u: Uri): String {
        var name = "未命名"
        try {
            contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val v = c.getString(idx)
                        if (!v.isNullOrBlank()) name = v
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return name
    }

    /** 源文件大小；拿不到返回 0（只影响空间检查和长任务提示，不影响功能） */
    protected fun sizeOf(u: Uri): Long {
        return try {
            contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) c.getLong(i) else 0L
                } else 0L
            } ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    /** 权限被"拒绝且不再询问"时的兜底：直接跳到本应用详情页 */
    protected fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        } catch (_: Throwable) {
            toast("打不开系统设置，请手动到「设置 → 应用」里授权")
        }
    }
}
