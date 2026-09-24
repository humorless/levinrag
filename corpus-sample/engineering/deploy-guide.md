# 部署指南

本指南說明庫存服務（inventory-svc）的部署流程。API 行為請參閱 [API v2 規格書](specs/api-v2.md)。

## 環境

| 環境 | 網址 | 部署方式 |
|---|---|---|
| staging | https://inventory.staging.internal | 合併到 main 後自動部署 |
| production | https://inventory.internal | 手動觸發，需兩人核准 |

## 部署到 production

1. 確認 staging 上的 smoke test 全部通過。
2. 建立 release tag：

```bash
git tag -a v2.8.0 -m "release v2.8.0"
git push origin v2.8.0
```

3. 在 CI 上執行 `deploy-prod` job，並取得第二位工程師核准。
4. 部署完成後觀察 Grafana「inventory-svc」儀表板 15 分鐘。

## 回滾

若錯誤率超過 2% 或 p95 延遲超過 800 ms，立即回滾：

```bash
kubectl rollout undo deployment/inventory-svc -n prod
kubectl rollout status deployment/inventory-svc -n prod
```

回滾後依[事故處理手冊](incident-runbook.md)開立事故單。

## 部署時段

production 部署僅限週一至週四 10:00–16:00。週五、國定假日前一天不得部署。
