package com.sondo.app.data.api

import com.sondo.app.core.constants.ApiPaths
import com.sondo.app.core.http.Api
import com.sondo.app.core.http.ApiResult
import com.sondo.app.core.http.ReqOptions
import com.sondo.app.data.models.CreditRecordModel
import com.sondo.app.data.models.PageResult
import com.sondo.app.data.models.UserModel
import com.sondo.app.data.models.jMapStrict
import com.sondo.app.data.models.jRaw
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 认证与用户信息（Dart: lib/shared/data/datasources/auth_remote_datasource.dart `AuthRemoteDataSource`）。
 *
 * 返回约定：Dart 的 `Result<dynamic>` 里 data 是对象的（登录令牌等）返回 data 对象（不是对象时为空 JSONObject）；
 * 调用方不看 data 的返回 Unit。
 * 未移植：`uploadAvatar`（multipart 上传，参考工程内无调用方）。
 */
object AuthApi {

    /**
     * 用本地已存的 RSA userId（[ApiCrypto.rsaEncrypt] 的结果）问是否该自动进回归页。不带登录态。
     * Success(true/false) = 有/无资格；失败：Dart 返回 null（未知）。
     */
    suspend fun queryReturningUser(encryptedUserId: String): ApiResult<Boolean> =
        Api.post(ApiPaths.returningUser, JSONObject().put("k2", encryptedUserId), opts = ReqOptions.NoToken)
            .map { it.dataAny() == true }

    /** 向大陆 11 位手机号发验证码。不带登录态，避免旧 token 把未登录请求带进刷新。 */
    suspend fun sendSmsCode(mobile: String): ApiResult<Unit> =
        postUnauthenticated(ApiPaths.sendSmsCode, JSONObject().put("mobile", mobile)).asUnit()

    /** 短信登录或自动注册。成功时 data 含 accessToken / refreshToken。 */
    suspend fun smsLogin(mobile: String, code: String): ApiResult<JSONObject> =
        postUnauthenticated(ApiPaths.smsLogin, JSONObject().put("mobile", mobile).put("code", code)).asDataObject()

    /** 微信授权码登录。needBindMobile 为 true 时尚未登录，只能拿 bindToken 去绑手机号。 */
    suspend fun wechatLogin(code: String): ApiResult<JSONObject> =
        postUnauthenticated(ApiPaths.wechatLogin, JSONObject().put("code", code)).asDataObject()

    /** 微信 bindToken 加短信验证码完成绑定并登录。令牌在 data 下，不在 data.login。 */
    suspend fun wechatBindMobile(bindToken: String, mobile: String, code: String): ApiResult<JSONObject> =
        postUnauthenticated(
            ApiPaths.wechatBindMobile,
            JSONObject().put("bindToken", bindToken).put("mobile", mobile).put("code", code),
        ).asDataObject()

    /** 极光 loginToken 原样换 accessToken。不裁剪、不重放，失败后由调用方重新取号。 */
    suspend fun oneClickLogin(loginToken: String): ApiResult<JSONObject> =
        postUnauthenticated(ApiPaths.oneClickLogin, JSONObject().put("loginToken", loginToken)).asDataObject()

    /** 微信待绑定状态下，用新的极光 loginToken 验号并完成登录。已提交的 loginToken 不可复用。 */
    suspend fun wechatBindMobileOneClick(bindToken: String, loginToken: String): ApiResult<JSONObject> =
        postUnauthenticated(
            ApiPaths.wechatBindMobileOneClick,
            JSONObject().put("bindToken", bindToken).put("loginToken", loginToken),
        ).asDataObject()

    /** 登录前接口不带 Authorization。业务码非 0 由 Api 提示并返回 Failure。 */
    private suspend fun postUnauthenticated(path: String, data: JSONObject): ApiResult<JSONObject> =
        Api.post(path, data, opts = ReqOptions.NoToken)

    /** 用 Google idToken 直连后端登录。国内版不走这条（参考工程内无调用方）。 */
    suspend fun googleLogin(idToken: String): ApiResult<JSONObject> =
        Api.post(ApiPaths.authGoogleLogin, JSONObject().put("idToken", idToken)).asDataObject()

    /** 邮箱密码直连后端登录。国内版不走这条（参考工程内无调用方）。 */
    suspend fun emailLogin(email: String, password: String): ApiResult<JSONObject> =
        Api.post(ApiPaths.authEmailLogin, JSONObject().put("email", email).put("password", password)).asDataObject()

    /** 邮箱密码直连后端注册。国内版不走这条（参考工程内无调用方）。 */
    suspend fun register(email: String, password: String, captcha: String): ApiResult<JSONObject> =
        Api.post(
            ApiPaths.authEmailRegister,
            JSONObject().put("email", email).put("password", password).put("captcha", captcha),
        ).asDataObject()

    /** 请求后端向邮箱发送验证码（邮箱地址以 text/plain 作为请求体）。国内版不走这条（无调用方）。 */
    suspend fun sendEmailCode(email: String): ApiResult<Unit> =
        Api.post(
            ApiPaths.authEmailCaptcha,
            email,
            opts = ReqOptions(headers = mapOf("Content-Type" to "text/plain")),
        ).asUnit()

    /** 用户主动登出，通知后端使当前 token 失效；仅成功时调用方才清理本地 token 与登录态。 */
    suspend fun logout(): ApiResult<Unit> = Api.post(ApiPaths.authLogout).asUnit()

    /** 用本地 refreshToken 换新 accessToken。实际刷新由 Api 内部完成，这里只为对齐参考工程（无调用方）。 */
    suspend fun refreshToken(): ApiResult<JSONObject> = Api.post(ApiPaths.authRefreshToken).asDataObject()

    /** Discord OAuth 回调换取业务 token（无调用方）。 */
    suspend fun discordLogin(code: String): ApiResult<JSONObject> =
        Api.get(ApiPaths.authDiscordLogin, query = mapOf("code" to code)).asDataObject()

    /** 凭邮箱验证码提交新密码完成重置。国内版不走这条（无调用方）。 */
    suspend fun resetPassword(email: String, password: String, captcha: String): ApiResult<Unit> =
        Api.post(
            ApiPaths.authResetPassword,
            JSONObject().put("email", email).put("password", password).put("captcha", captcha),
        ).asUnit()

    /** 更新昵称/头像（PUT），两者均可选、按需部分更新；成功后调用方需自行重新拉取用户信息。 */
    suspend fun updateUserInfo(nickname: String?, avatar: String?): ApiResult<Unit> =
        Api.put(ApiPaths.userUpdate, bodyOf("nickname" to nickname, "avatar" to avatar)).asUnit()

    /**
     * 拉取当前登录用户信息。
     * 失败、data 为空或缺 id（Dart 解析抛异常）：Dart 返回 null，调用方按约定做有限次轮询重试。
     */
    suspend fun getUserInfo(): ApiResult<UserModel> =
        Api.get(ApiPaths.userGet).parse { UserModel.fromJsonOrNull(it.dataObject()) }

    /** 注销当前账号；成功后调用方需自行清理本地 token、用户态与播放器等业务缓存。 */
    suspend fun delUser(): ApiResult<Unit> = Api.post(ApiPaths.authDelUser).asUnit()

    /**
     * 分页拉取积分变动记录，[addStatus] 为空查全部、非空按增加/扣减类型过滤（无调用方；
     * member 模块有自己的同接口实现）。任一条字段不全时整页失败（与 Dart 一致）。
     */
    suspend fun getPointRecord(
        pageNo: Int,
        pageSize: Int,
        addStatus: Boolean?,
    ): ApiResult<PageResult<CreditRecordModel>> =
        Api.get(
            ApiPaths.pointRecordPage,
            query = queryOf("pageNo" to pageNo, "pageSize" to pageSize, "addStatus" to addStatus),
        ).parse { env -> PageResult.fromDataStrict(env.dataObject()) { CreditRecordModel.fromJsonOrNull(it) } }

    /** 用兑换码兑换礼包/积分；成功后调用方需刷新用户信息使兑换结果生效。 */
    suspend fun exchangeGift(cdkey: String): ApiResult<Unit> =
        Api.post(ApiPaths.exchangeGift, JSONObject().put("cdkey", cdkey)).asUnit()

    /** 上报分享行为并领取分享奖励积分（无调用方）。 */
    suspend fun shareAndSendPoints(): ApiResult<Unit> = Api.get(ApiPaths.shareAndSendPoints).asUnit()

    /**
     * 音乐风格标签字典，每项 `{genre, genreId}`，供编辑歌曲时选择风格。
     * 失败或 data.prompts 不是数组：Dart 返回空列表。
     */
    suspend fun musicGenerList(): ApiResult<List<JSONObject>> =
        Api.get(ApiPaths.musicGenerList).parse { env ->
            val prompts = env.dataObject()?.jRaw("prompts") as? JSONArray
            prompts?.jMapStrict { e -> JSONObject().put("genre", e.jRaw("genre")).put("genreId", e.jRaw("genreId")) }
        }
}
