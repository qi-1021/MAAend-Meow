#!/system/bin/sh
# 手机侧「远程调试中继客户端」：**出站**连到公网桥 → 长轮询取命令 → 本地 CLI 执行 → 回结果。
#
# 为什么这样设计：手机在蜂窝/CGNAT/异网下无法被入站连接（也无法被同网段直连），
# 只有"手机拨出去"才可靠；握手成功后远端即可经公网发命令。
#
# 用法：sh relay_client.sh <relay_base_url> <token>
#   例：sh relay_client.sh https://maaendset.qiisme1021.space v2SZGz5...

RELAY="$1"
TOKEN="$2"
CLI_HOST=127.0.0.1
CLI_PORT=7777
SID=""

log() { echo "[relay] $(date +%H:%M:%S) $*"; }

attach() {
  SID=$(curl -sS -m 30 -X POST "$RELAY/attach" -H "X-Token: $TOKEN" 2>/dev/null | head -1 | tr -d '\r\n')
  case "$SID" in
    [0-9a-f][0-9a-f]*) log "attached sid=$SID";;
    *) SID=""; log "attach failed: $(curl -sS -m 10 -X POST "$RELAY/attach" -H "X-Token: $TOKEN" 2>&1 | head -1)";;
  esac
}

# 本地 CLI：一次连接把 auth+命令写完。
# 响应有三段、每段以 --END-- 结尾：① 欢迎语 ② auth 结果 ③ 命令结果。
# 只回传**第 3 段**（命令结果）——第 1 段是欢迎语、第 2 段是 auth 回执，都不是远端要的。
exec_cmd() {
  {
    printf 'auth %s\n' "$TOKEN"
    printf '%s\n' "$1"
  } | timeout 90 nc -w 5 "$CLI_HOST" "$CLI_PORT" 2>/dev/null \
    | awk 'BEGIN{n=0} /^--END--$/{n++; if(n>=3) exit; next} {if(n>=2) print}'
}

log "client start relay=$RELAY"
while true; do
  [ -z "$SID" ] && attach
  if [ -z "$SID" ]; then sleep 5; continue; fi

  RESP=$(curl -sS -m 40 "$RELAY/pull?sid=$SID&timeout=25" -H "X-Token: $TOKEN" 2>/dev/null | tr -d '\r')
  RC=$?
  if [ $RC -ne 0 ] || [ -z "$RESP" ]; then sleep 1; continue; fi

  RID=$(printf '%s\n' "$RESP" | head -1)
  CMD=$(printf '%s\n' "$RESP" | tail -n +2)
  case "$RID" in
    [0-9a-f][0-9a-f]*) : ;;
    *) sleep 1; continue;;
  esac

  log "run: $CMD"
  OUT=$(exec_cmd "$CMD")
  curl -sS -m 60 -X POST "$RELAY/result?sid=$SID&rid=$RID" -H "X-Token: $TOKEN" \
       --data-binary "$OUT" >/dev/null 2>&1
  log "done rid=$RID bytes=${#OUT}"
done
