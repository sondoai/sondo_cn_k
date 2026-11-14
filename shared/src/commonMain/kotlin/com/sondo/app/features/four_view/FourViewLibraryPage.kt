package com.sondo.app.features.four_view

import com.sondo.app.base.BasePager
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AssetsImage
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.constants.c
import com.sondo.app.core.constants.whiteA
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.utils.DevLog
import com.sondo.app.features.four_view.data.FourViewApi
import com.sondo.app.features.four_view.data.FourViewEntity
import com.sondo.app.features.four_view.data.FourViewStatus
import com.sondo.app.service.PollingService
import com.sondo.app.service.UserStore
import com.sondo.app.ui.atoms.SDAssetImage
import com.sondo.app.ui.atoms.SDImage
import com.sondo.app.ui.atoms.SDLoading
import com.sondo.app.ui.atoms.SDMusicLoading
import com.sondo.app.ui.list.SDRefreshList
import com.sondo.app.ui.list.SDRefreshListView
import com.sondo.app.ui.nav.SDNavBar
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 四视图角色库列表页（Dart: four_view/presentation/pages/library/library_page.dart + four_view_library_controller.dart）。
 *
 * 参数（Dart `FourViewLibraryArgs`）：`{imageUrl?: String}` —— 进入时预选中的角色图 URL（与 roleImg 相等的角色高亮）。
 * 结果：选中一个生成成功的角色后 `nav.pop({role: RoleModel json})`（`{id, roleImg, roleImgDesc, coverImage, createTime, updateTime, status}`）；
 * 直接返回时结果为 null。
 */
@Page(Routes.fourView)
internal class FourViewLibraryPage : BasePager() {

    /** 当前选中的角色 ID，-1 表示未选中。 */
    var selectedRoleId by observable(-1L)

    /** 首屏加载中，用于展示全屏 loading。 */
    var isFirstLoading by observable(true)

    /** 进入页面时传入的图片 URL，用于匹配预选角色。 */
    private var imageUrl = ""

    val dataList by observableList<FourViewEntity>()

    private var pageNo = 1
    private val pageSize = 10
    private var loading = false
    /** 表单保存成功时正有请求在途：等它结束后再刷新第一页（Dart onRefresh 总会重载） */
    private var pendingRefresh = false
    private var noMore = false
    private var listRef: ViewRef<SDRefreshListView>? = null

    private val roleGeneratingIds = LinkedHashSet<Long>()

    override fun created() {
        super.created()
        imageUrl = pagerData.params.optString("imageUrl", "")
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        if (!readyCalled) {
            readyCalled = true
            onRefresh()
        }
    }

    private var readyCalled = false

    override fun pageWillDestroy() {
        PollingService.stopTask(POLLING_TASK_ID)
        super.pageWillDestroy()
    }

    // ------------------------------------------------------------------ 数据（RefreshMixin）

    fun onRefresh() {
        if (loading) return
        pageNo = 1
        loadPage(isRefresh = true)
    }

    fun onLoad() {
        if (loading || noMore) {
            listRef?.view?.endLoadMore(noMore, true)
            return
        }
        pageNo++
        loadPage(isRefresh = false)
    }

    private fun loadPage(isRefresh: Boolean) {
        loading = true
        val requestPage = pageNo
        launch {
            val list = loadData(requestPage)
            loading = false
            val failed = list == null
            val items = list ?: emptyList()
            if (isRefresh) {
                dataList.clear()
                dataList.addAll(items)
            } else {
                if (failed) pageNo-- else dataList.addAll(items)
            }
            noMore = !failed && items.size < pageSize
            if (isRefresh) listRef?.view?.endRefresh(noMore) else listRef?.view?.endLoadMore(noMore, !failed)
            if (pendingRefresh) {
                pendingRefresh = false
                onRefresh()
            }
        }
    }

    /** 拉取一页；失败返回 null（Dart 返回空列表）。 */
    private suspend fun loadData(page: Int): List<FourViewEntity>? {
        val result = FourViewApi.getPage(page, pageSize)
        val data = result.getOrNull()
        if (data == null || data.total < 0) {
            DevLog.logger("FourViewLibraryPage loadData error")
            if (isFirstLoading) isFirstLoading = false
            return if (data == null) null else emptyList()
        }
        if (isFirstLoading) isFirstLoading = false
        if (page == 1) roleGeneratingIds.clear()
        data.list.forEach { item ->
            if (imageUrl.isNotEmpty() && item.roleImg == imageUrl) selectedRoleId = item.id
            if (item.status == FourViewStatus.generating) roleGeneratingIds.add(item.id)
        }
        if (roleGeneratingIds.isNotEmpty()) loopRoleData()
        return data.list
    }

    /** 轮询生成中的角色状态（12 s，任务 id 与 Dart 相同）。 */
    private fun loopRoleData() {
        val page = this
        PollingService.registerTask(
            taskId = POLLING_TASK_ID,
            intervalMs = 12_000,
            startImmediately = false,
            shouldContinue = { roleGeneratingIds.isNotEmpty() },
            task = {
                page.runOnPage {
                    val list = FourViewApi.getByIds(roleGeneratingIds.joinToString(",")).getOrNull() ?: emptyList()
                    if (list.isEmpty()) return@runOnPage
                    for (data in list) {
                        val index = dataList.indexOfFirst { it.id == data.id }
                        if (data.status == FourViewStatus.success || data.status == FourViewStatus.failed) {
                            roleGeneratingIds.remove(data.id)
                            if (index >= 0) {
                                dataList[index] = dataList[index].copy(
                                    status = data.status,
                                    roleImg = data.roleImg,
                                    roleImgDesc = data.roleImgDesc,
                                )
                            }
                        } else if (data.status == FourViewStatus.generating && index >= 0) {
                            val item = dataList[index]
                            if (item.status != FourViewStatus.generating) {
                                dataList[index] = item.copy(
                                    status = data.status,
                                    roleImg = data.roleImg,
                                    roleImgDesc = data.roleImgDesc,
                                )
                            }
                        }
                    }
                }
            },
        )
    }

    // ------------------------------------------------------------------ 交互

    /** 选中角色并返回给调用页。 */
    fun selectRole(role: FourViewEntity) {
        if (!role.isSuccess) return
        selectedRoleId = if (selectedRoleId == role.id) -1L else role.id
        if (role.id > 0) nav.pop(JSONObject().put("role", role.toJson()))
    }

    /** 跳转添加角色表单，需同意上传声明且订阅。 */
    fun goAddRole() {
        launch {
            val isAgree = UserStore.showAgree(StorageKey.isAgreeUpload, "upload", this@FourViewLibraryPage)
            if (!isAgree) return@launch
            if (!UserStore.isSubscribed) {
                nav.push(Routes.subscribe)
                return@launch
            }
            nav.push(Routes.fourViewForm) { onFormResult(it) }
        }
    }

    /** 跳转编辑角色表单。 */
    fun goEditRole(role: FourViewEntity) {
        nav.push(Routes.fourViewForm, JSONObject().put("role", role.toJson())) { onFormResult(it) }
    }

    /** 表单页创建 / 删除成功后刷新列表（Dart 由表单页直接调 onRefresh）。 */
    private fun onFormResult(result: JSONObject?) {
        if (result?.optBoolean("result", false) != true) return
        if (loading) pendingRefresh = true else onRefresh()
    }

    // ------------------------------------------------------------------ UI

    val cellWidth: Float get() = (screenWidth - 32f - 12f) / 2f
    val cellHeight: Float get() = cellWidth * 120f / 158f

    override fun content(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f) }
            SDNavBar {
                attr {
                    title = "role_library_title".tr
                    titleSize = 18f
                }
            }
            View {
                attr {
                    flex(1f)
                    paddingBottom(ctx.safeBottom)
                }
                vif({ ctx.isFirstLoading && ctx.dataList.isEmpty() }) {
                    View {
                        attr {
                            flex(1f)
                            allCenter()
                        }
                        SDMusicLoading()
                    }
                }
                velse {
                    SDRefreshList {
                        ref { ctx.listRef = it }
                        attr {
                            flex(1f)
                            columnCount = 2
                            listWidth = ctx.screenWidth
                            itemSpacing = 12f
                            lineSpacing = 12f
                            contentPaddingH = 16f
                            contentPaddingTop = 4f
                            contentPaddingBottom = 4f
                            bounces = false
                        }
                        event {
                            onRefresh { ctx.onRefresh() }
                            onLoadMore { ctx.onLoad() }
                        }
                        items {
                            addRoleCard(ctx)
                            vfor({ ctx.dataList }) { role ->
                                roleCard(ctx, role)
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val POLLING_TASK_ID = "role_generating_task"
    }
}

private fun ViewContainer<*, *>.addRoleCard(ctx: FourViewLibraryPage) {
    // Dart 外层 2 宽透明描边：灰底内缩 2，与角色卡（2 宽选中描边）对齐
    View {
        attr {
            size(ctx.cellWidth, ctx.cellHeight)
            padding(2f)
        }
        event { click { ctx.goAddRole() } }
        View {
            attr {
                flex(1f)
                borderRadius(12f)
                backgroundColor(whiteA(0.1f))
                allCenter()
            }
            SDAssetImage(AssetsImage.addRole, 32f, 30f)
            Text {
                attr {
                    marginTop(12f)
                    text("role_library_add_role".tr)
                    fontSize(10f)
                    fontFamily("Poppins")
                    color(whiteA(0.3f))
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.roleCard(ctx: FourViewLibraryPage, role: FourViewEntity) {
    val selectColor = c(0xFFB55AFFL)
    View {
        attr {
            size(ctx.cellWidth, ctx.cellHeight)
            borderRadius(14f)
            val selected = ctx.selectedRoleId == role.id
            border(com.tencent.kuikly.core.base.Border(2f, com.tencent.kuikly.core.base.BorderStyle.SOLID, if (selected) selectColor else Color.TRANSPARENT))
            backgroundColor(if (selected) selectColor else whiteA(0.1f))
            overflow(true)
        }
        event {
            click {
                if (role.isFailed || role.isSuccess) ctx.selectRole(role)
            }
        }
        View {
            attr {
                flex(1f)
                borderRadius(10f)
                overflow(true)
            }
            // 图片区 + 编辑行
            View {
                attr { flex(1f) }
                if (role.roleImg.isNotEmpty()) {
                    SDImage {
                        attr {
                            flex(1f)
                            borderRadius(12f, 12f, 0f, 0f)
                            url = role.roleImg
                        }
                    }
                } else {
                    View { attr { flex(1f) } }
                }
                View {
                    attr {
                        height(30f)
                        paddingLeft(10f)
                        paddingRight(10f)
                        flexDirectionRow()
                        alignItemsCenter()
                    }
                    event { click { ctx.goEditRole(role) } }
                    Text {
                        attr {
                            flex(1f)
                            text("edit_role".tr)
                            fontSize(10f)
                            fontFamily("Poppins-Medium")
                            color(Color.WHITE)
                            lines(1)
                            textOverFlowTail()
                        }
                    }
                    SDAssetImage(AssetsImage.arrowRight, 12f, tint = whiteA(0.5f))
                }
            }
            // 右上角勾选
            View {
                attr {
                    absolutePosition(top = 0f, right = 0f)
                    padding(8f, 12f, 12f, 8f)
                }
                Image {
                    attr {
                        size(16f, 16f)
                        resizeContain()
                        src(ImageUri.commonAssets(if (ctx.selectedRoleId == role.id) AssetsImage.roleChecked else AssetsImage.roleCheck))
                    }
                }
            }
            if (role.isGenerating || role.isFailed) {
                View {
                    attr {
                        absolutePositionAllZero()
                        allCenter()
                    }
                    // Dart 遮罩图片会吃掉命中，下面的「编辑角色」点不到，点击落到卡片本身
                    event {
                        click {
                            if (role.isFailed) ctx.selectRole(role)
                        }
                    }
                    Image {
                        attr {
                            absolutePositionAllZero()
                            src(ImageUri.commonAssets(AssetsImage.roleError))
                            resizeCover()
                        }
                    }
                    if (role.isGenerating) {
                        SDLoading { attr { size = 32f; color = whiteA(0.8f) } }
                    } else {
                        SDAssetImage(AssetsImage.mvFailedIcon, 32f, tint = whiteA(0.7f))
                    }
                    Text {
                        attr {
                            marginTop(10f)
                            text(if (role.isGenerating) "generating".tr else "failed".tr)
                            fontSize(10f)
                            fontWeightMedium()
                            color(whiteA(0.8f))
                        }
                    }
                }
            }
        }
    }
}
