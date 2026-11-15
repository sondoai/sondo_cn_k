# 鸿蒙华为支付：客户端约定的后端接口

各端支付渠道：**iOS = Apple 内购，Android = 支付宝，鸿蒙 = 华为支付（Payment Kit）**。

鸿蒙客户端已按本文档接好（`shared/.../data/api/PayApi.kt` 按平台选路径，`PayService` 复用支付宝的建单 → 拉起收银台 → 轮询查单 → 补单流程）。
下面 4 个接口**目前后端还没有**，是客户端拟定的约定：请求、回包、业务码都与现有支付宝接口**同构**，后端照此实现即可；
如果需要改路径或字段，告诉客户端改 `ApiPaths.huawei*` / `AlipayModels.kt`。

| 用途 | 华为支付（鸿蒙，新增） | 对应的支付宝接口（Android，已有） |
|---|---|---|
| 积分包下单 | `POST /member/huawei/trade/create` | `POST /member/alipay/trade/create` |
| 会员自动续费签约 | `POST /member/huawei/subscription/create` | `POST /member/alipay/subscription/create` |
| 查单 | `GET /member/huawei/order/{orderId}` | `GET /member/alipay/order/{orderId}` |
| 取消自动续费（解约） | `POST /member/huawei/subscription/cancel` | `POST /member/alipay/subscription/cancel` |
| 订阅管理状态 | `GET /member/order/subscriptionManagement`（共用，新增 provider 取值） | 同左 |
| 商品列表 | `GET /member/product/getList?platform=12`（共用，未区分渠道） | 同左 |

所有接口都是登录后接口，信封 `{code, msg, data}`；baseUrl 已含 `/app-api`。

## 1. 积分包下单 `POST /member/huawei/trade/create`

请求：`{"productId": "<商品 ID>"}`

回包 `data`：

```json
{
  "orderId": "业务订单号",
  "orderStr": "<华为支付 orderStr，JSON 字符串>",
  "payAmount": 6.00,
  "expireTime": "2026-11-15 18:00:00"
}
```

- `orderStr`：商户服务器调华为支付服务端**预下单**拿到 `prepay_id` 后，按 Payment Kit《基础支付》文档拼装并**签名**的 JSON 字符串
  （字段形如 `app_id`、`merc_no`、`prepay_id`、`timestamp`、`noncestr`、`sign`、`auth_id`，以华为官方文档为准）。
  客户端原样传给 `paymentService.requestPayment(context, orderStr)`，不解析、不改写。
- `payAmount`：实付金额（首期优惠时与标价不同），只用于埋点上报。
- 客户端要求 `orderId`、`orderStr` 都非空才拉起收银台。

## 2. 会员自动续费签约 `POST /member/huawei/subscription/create`

请求：`{"productId": "<会员商品 ID>"}`

回包 `data`：

```json
{
  "orderId": "业务订单号",
  "contractStr": "<华为支付 contractStr，JSON 字符串>",
  "payAmount": 18.00,
  "expireTime": "2026-11-15 18:00:00"
}
```

- `contractStr`：商户服务器调华为支付服务端**预签约**（签约代扣 / 支付并签约）后，按 Payment Kit 签约文档拼装并签名的字符串，
  客户端原样传给 `paymentService.requestContract(context, contractStr)`。
- 签约成功后的首期扣款、之后每期续扣、签约 / 扣款结果回调（华为要求 SM2 验签）都在后端完成；
  客户端只通过「查单」看这笔 `orderId` 的状态。
- 客户端要求 `orderId`、`contractStr` 都非空才拉起签约收银台。

## 3. 查单 `GET /member/huawei/order/{orderId}`

回包 `data`（与支付宝查单完全相同）：

```json
{ "orderId": "…", "status": "WAITING", "productId": "…", "points": 100 }
```

| `status` | 客户端行为 |
|---|---|
| `WAITING` | 待付款：继续轮询（3 秒一次、最多 20 次）；用户再点同一商品时用本地保存的 `orderStr` / `contractStr` 重开收银台 |
| `PROCESSING` | 已付款、权益发放中：提示「支付成功，权益到账中」，继续轮询 |
| `SUCCESS` | 已到账：刷新用户信息、弹购买成功、清本地待单 |
| `CLOSED` | 已关闭：提示「未完成支付」，清本地待单，允许重新下单 |
| `REFUNDED` | 已退款：提示「已退款」，清本地待单 |

**权益只认这里的 `status`**：客户端收到华为收银台的成功回调后也只是开始查单，不会自己发权益。

## 4. 取消自动续费 `POST /member/huawei/subscription/cancel`

请求体为空。回包 `data`：`{"autoRenew": false, "currentPeriodEnd": "2026-12-15"}`。
成功只表示不再续扣，当前周期权益仍在。没有可取消的续费时返回业务码 `1046000006`。

## 5. 订阅管理状态 `GET /member/order/subscriptionManagement`（已有接口，新增取值）

鸿蒙用户是华为支付自动续费、且可以取消时，返回：

```json
{ "provider": "HUAWEI", "action": "CANCEL_HUAWEI_AUTO_RENEW", "reason": "" }
```

客户端只有在 `provider=HUAWEI && action=CANCEL_HUAWEI_AUTO_RENEW`（或支付宝的 `ALIPAY` / `CANCEL_ALIPAY_AUTO_RENEW`）时才弹「确认取消自动续费」。

## 业务码（沿用支付宝那一套，客户端按同样的码给提示）

| code | 含义 | 鸿蒙提示 |
|---|---|---|
| `1046000001` | 渠道未开通（客户端会关掉本次进程里的下单入口） | 华为支付暂未开通 |
| `1046000002` | 商品不可购买 | 该商品暂不可购买 |
| `1046000003` | 渠道繁忙 | 华为支付暂时无法响应，请稍后重试 |
| `1046000004` | 已开通自动续费 | 你已开通自动续费 |
| `1046000005` | 订单不存在（查单） | 订单不存在 |
| `1046000006` | 没有可取消的自动续费 | 当前没有可取消的自动续费 |
| `1046000008` / `1046000009` | 幸运月卡仅首开 / 首月优惠不可用 | 同 Android |
| `1009000004` / `1009000005` | 积分包仅会员 / 会员已到期（客户端跳订阅页） | 同 Android |
| `1009496001~1009496005`、`1009280003`、`1009280005` | 回归优惠相关 | 同 Android |
| `1004040004` | 未绑定手机号 | 请先绑定手机号 |

## 还需要后端 / 运营确认的点

1. **商品**：鸿蒙现在和 Android 用同一份国内商品（`platform=12`）。如果华为支付要单独定价或单独上架，给一个新的 `platform` 值，客户端改 `PayApi.getCnSubscriptionProducts`。
2. **`Request-Source` 请求头**：鸿蒙目前和 Android 一样报 `2`（`AppEnv.requestSource`）。后端如需区分鸿蒙，请分配专用值。
3. **华为侧开通**：AGC 开通华为支付（商户号、商户证书 / 公私钥），在 AGC 登记应用签名证书指纹（否则客户端报 `1001860002` 应用未被授权）；签约代扣需要单独申请开通。
4. **审核政策**：会员和积分属于虚拟商品，上架前请和华为应用市场确认允许走 Payment Kit（而不是 IAP Kit）。

## 客户端侧的失败处理（供联调对照）

| 收银台结果（`BusinessError.code`） | 客户端行为 |
|---|---|
| 成功（Promise resolve） | 开始轮询查单 |
| `1001930000` 用户取消 | 提示「未完成支付」，只查一次单，订单留在本地，再点同一商品可重开 |
| `1001930001` 支付失败 / `1001930002` 交易已处理 / `1001930010` 重复请求 / `1001930011` 网络错误 / 其它 | 提示「华为支付暂时无法响应」，仍然轮询查单（以后端状态为准） |
