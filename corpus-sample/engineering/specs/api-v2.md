# 庫存 API v2 規格書

Inventory API v2 提供料號查詢與庫存異動功能。料號格式請見 [[料號編碼規則]]。

## 認證

所有請求需帶 `Authorization: Bearer <token>`。Token 由內部 SSO 簽發，有效期限 12 小時。

## Endpoints

| Method | Path | 說明 |
|---|---|---|
| GET | /v2/items/{sku} | 查詢單一料號 |
| GET | /v2/items?category=A | 依類別列出料號 |
| POST | /v2/stock/adjust | 調整庫存 |
| GET | /v2/stock/{sku}/history | 查詢庫存異動紀錄 |

## 範例：查詢料號

```bash
curl -H "Authorization: Bearer $TOKEN" \
  https://inventory.internal/v2/items/SKU-A1234
```

回應：

```json
{
  "sku": "SKU-A1234",
  "name": "鋁合金外殼 13 吋",
  "category": "A",
  "on_hand": 420,
  "reorder_point": 100
}
```

## 範例：調整庫存

```python
import requests

resp = requests.post(
    "https://inventory.internal/v2/stock/adjust",
    headers={"Authorization": f"Bearer {token}"},
    json={"sku": "SKU-B2210", "delta": -5, "reason": "RMA"},
    timeout=10,
)
resp.raise_for_status()
```

`delta` 為正數表示入庫、負數表示出庫；`reason` 必填，可用值為 `PO`、`SALE`、`RMA`、`ADJ`。

## 錯誤碼

| HTTP | code | 說明 |
|---|---|---|
| 400 | E1001 | 料號格式錯誤 |
| 404 | E1002 | 料號不存在 |
| 409 | E2001 | 庫存不足，無法出庫 |
| 429 | E9001 | 超過速率限制（每分鐘 600 次） |

## 版本相容性

v1 API 將於 2026 年 6 月 30 日停止服務。v1 的 `qty` 欄位在 v2 改名為 `on_hand`。
