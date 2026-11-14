package com.sondo.app.features.music_video

import com.sondo.app.ui.input.SDKeyboardAwareScrollView
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.IScrollerViewEventObserver
import com.tencent.kuikly.core.views.ScrollParams

/**
 * 校验失败后滚到区块（Dart core/utils/form_field_locator.dart `scrollToSection`，alignment 0.2）：
 * 目标顶部落在视口 20%（扣掉自身高度）的位置。
 */
class MvSectionAnchors {
    /** 滚动区重建（活动页区块顺序变化）时换新引用，旧的偏移监听随旧视图作废。 */
    var scrollRef: ViewRef<SDKeyboardAwareScrollView>? = null
        set(value) {
            field = value
            observer = null
        }
    val refs = HashMap<MvFormField, ViewRef<DivView>>()

    fun scrollTo(field: MvFormField, alignment: Float = 0.2f) {
        val target = refs[field]?.view ?: return
        val scroller = scrollRef?.view?.scroller ?: return
        val content = scroller.contentView ?: return
        val inContent = target.convertFrame(target.frame, content)
        val viewport = scroller.frame.height
        val maxOffset = (content.frame.height - viewport).coerceAtLeast(0f)
        val offset = (inContent.y - alignment * (viewport - inContent.height)).coerceIn(0f, maxOffset)
        scroller.setContentOffset(0f, offset, true)
    }

    private var observer: IScrollerViewEventObserver? = null

    /** 订阅主滚动区的纵向偏移（活动页导航底色）。滚动视图尚未创建时返回 false。 */
    fun observeScroll(onOffset: (Float) -> Unit): Boolean {
        val scroller = scrollRef?.view?.scroller ?: return false
        if (observer != null) return true
        val o = object : IScrollerViewEventObserver {
            override fun onContentOffsetDidChanged(contentOffsetX: Float, contentOffsetY: Float, params: ScrollParams) {
                onOffset(contentOffsetY)
            }

            override fun subViewsDidLayout() {}
        }
        observer = o
        scroller.addScrollerViewEventObserver(o)
        return true
    }

    fun scrollToBottom() {
        val scroller = scrollRef?.view?.scroller ?: return
        val content = scroller.contentView ?: return
        val maxOffset = (content.frame.height - scroller.frame.height).coerceAtLeast(0f)
        scroller.setContentOffset(0f, maxOffset, true)
    }
}
