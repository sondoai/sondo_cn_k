#!/usr/bin/env bash
# 蒲公英上传（流程同参考工程 scripts/build_app.sh）：
#   getCOSToken → 上传到 COS → buildInfo 轮询直到发布完成 →（鸿蒙）uploadHarmonyCert 上传 p12 供蒲公英签 manifest
# 由 build_app.sh source；需要 common.sh 的 log/die。
#
# 配置（scripts/build.local.sh）：
#   PGYER_API_KEY          必填
#   PGYER_INSTALL_TYPE     1=公开 2=密码 3=邀请，默认 2
#   PGYER_PASSWORD         安装密码（INSTALL_TYPE=2 时）
#   OHOS_P12 / OHOS_P12_PASSWORD / OHOS_KEY_ALIAS   鸿蒙上传 p12 用（与 hap 签名证书一致）

PGYER_API_BASE_URL="https://www.pgyer.com/apiv2"
PGYER_UPLOAD_BASE_URL="https://upload.pgyer.com/apiv2"

# 从 JSON 里取字段：json_get '<json>' data.key
json_get() {
  python3 -c '
import json, sys
try:
    obj = json.loads(sys.argv[1])
except Exception:
    sys.exit(0)
for k in sys.argv[2].split("."):
    if isinstance(obj, dict) and k in obj:
        obj = obj[k]
    else:
        sys.exit(0)
print(obj if not isinstance(obj, (dict, list)) else json.dumps(obj))
' "$1" "$2"
}

pgyer_require_key() {
  if [[ -z "${PGYER_API_KEY:-}" ]]; then
    die "未配置蒲公英 API Key：复制 scripts/build.local.sh.example 为 scripts/build.local.sh 并填写 PGYER_API_KEY"
  fi
}

# pgyer_upload <file> <apk|ipa|hap> <更新描述>
# 成功后设置 PGYER_BUILD_KEY（无扩展名，鸿蒙传证书要用）与 PGYER_URL
pgyer_upload() {
  local file="$1" build_type="$2" desc="$3"
  pgyer_require_key
  [[ -f "$file" ]] || die "待上传文件不存在: $file"

  logTitle "蒲公英 1/3：获取上传凭证"
  local args=(-s
    --form-string "_api_key=${PGYER_API_KEY}"
    --form-string "buildType=${build_type}"
    --form-string "buildInstallType=${PGYER_INSTALL_TYPE:-2}"
    --form-string "buildUpdateDescription=${desc}")
  if [[ -n "${PGYER_PASSWORD:-}" ]]; then
    args+=(--form-string "buildPassword=${PGYER_PASSWORD}")
  fi
  local resp
  resp="$(curl "${args[@]}" "$PGYER_API_BASE_URL/app/getCOSToken")"
  local code endpoint key signature token
  code="$(json_get "$resp" code)"
  endpoint="$(json_get "$resp" data.endpoint)"
  key="$(json_get "$resp" data.key)"
  [[ -z "$key" ]] && key="$(json_get "$resp" data.params.key)"
  signature="$(json_get "$resp" data.params.signature)"
  token="$(json_get "$resp" data.params.x-cos-security-token)"
  if [[ "$code" != "0" || -z "$endpoint" || -z "$key" || -z "$signature" || -z "$token" ]]; then
    die "获取上传凭证失败: $resp"
  fi

  logTitle "蒲公英 2/3：上传 $(basename "$file")"
  local http
  http="$(curl -o /dev/null -w '%{http_code}' --progress-bar \
    --form-string "key=${key}" \
    --form-string "signature=${signature}" \
    --form-string "x-cos-security-token=${token}" \
    --form-string "x-cos-meta-file-name=$(basename "$file")" \
    -F "file=@${file}" "$endpoint")"
  [[ "$http" == "204" ]] || die "上传失败，HTTP $http"

  logTitle "蒲公英 3/3：等待发布"
  # buildInfo 用 COS key 查；鸿蒙证书接口要无扩展名的 buildKey（发布完成后以返回值为准）
  PGYER_BUILD_KEY="${key%.*}"
  PGYER_URL=""
  local i shortcut
  for i in $(seq 1 90); do
    resp="$(curl -s "$PGYER_API_BASE_URL/app/buildInfo?_api_key=${PGYER_API_KEY}&buildKey=${key}")"
    code="$(json_get "$resp" code)"
    if [[ "$code" == "0" ]]; then
      local bk
      bk="$(json_get "$resp" data.buildKey)"
      [[ -n "$bk" ]] && PGYER_BUILD_KEY="$bk"
      shortcut="$(json_get "$resp" data.buildShortcutUrl)"
      [[ -n "$shortcut" ]] && PGYER_URL="https://www.pgyer.com/${shortcut}"
      log "发布完成 buildKey=${PGYER_BUILD_KEY}"
      [[ -n "$PGYER_URL" ]] && log "下载地址: $PGYER_URL"
      return 0
    fi
    if [[ "$code" == "1216" ]]; then
      die "蒲公英发布失败: $resp"
    fi
    log "处理中...（${i}/90，code=${code:-n/a}）"
    sleep 2
  done
  die "等待发布超时，最后响应: $resp"
}

# 鸿蒙：hap 发布完成后上传 p12，蒲公英用它签 manifest（必须与 hap 的签名证书一致）
pgyer_upload_harmony_cert() {
  local p12="${OHOS_P12:-}" pwd="${OHOS_P12_PASSWORD:-}" alias="${OHOS_KEY_ALIAS:-}"
  if [[ -z "$p12" || ! -f "$p12" || -z "$pwd" ]]; then
    log "警告: 未配置 OHOS_P12 / OHOS_P12_PASSWORD，跳过 p12 上传；蒲公英上的鸿蒙包可能装不上"
    return 0
  fi
  if command -v keytool >/dev/null 2>&1; then
    keytool -list -keystore "$p12" -storetype PKCS12 -storepass "$pwd" >/dev/null 2>&1 \
      || die "p12 密码打不开证书（keytool 校验失败）: $p12"
  fi

  logTitle "蒲公英：上传鸿蒙 p12"
  local args=(-s -F "_api_key=${PGYER_API_KEY}" -F "buildKey=${PGYER_BUILD_KEY}" -F "password=${pwd}")
  [[ -n "$alias" ]] && args+=(-F "alias=${alias}")
  args+=(-F "file=@${p12}")
  local resp code
  resp="$(curl "${args[@]}" "$PGYER_UPLOAD_BASE_URL/app/uploadHarmonyCert")"
  code="$(json_get "$resp" code)"
  case "$code" in
    0) log "p12 上传成功" ;;
    3008) die "蒲公英打不开 p12（密码或文件不对）: $resp" ;;
    3001) die "buildKey 无效（需无扩展名 buildKey=${PGYER_BUILD_KEY}）: $resp" ;;
    *) die "uploadHarmonyCert 失败: $resp" ;;
  esac
}
