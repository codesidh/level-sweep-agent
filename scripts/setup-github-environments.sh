#!/usr/bin/env bash
# scripts/setup-github-environments.sh
#
# One-time bootstrap of GitHub Environments for the level-sweep-agent repo,
# referenced by .github/workflows/iac.yml.
#
# Requires:
#   - gh CLI authenticated as a repo admin (gh auth status)
#   - jq on PATH
#
# Idempotent: re-running is safe.

set -euo pipefail

REPO="${REPO:-codesidh/level-sweep-agent}"
ADMIN_LOGIN="${ADMIN_LOGIN:-codesidh}"

echo "→ Ensuring GitHub Environments exist on $REPO"
for env in dev stage prod; do
  gh api -X PUT "repos/$REPO/environments/$env" >/dev/null
  echo "  ✓ $env"
done

ADMIN_ID=$(gh api "users/$ADMIN_LOGIN" -q .id)

echo "→ Configuring 'prod' with required-reviewer ($ADMIN_LOGIN) + 5-min wait timer"
gh api -X PUT "repos/$REPO/environments/prod" \
  --input - <<JSON >/dev/null
{
  "wait_timer": 5,
  "deployment_branch_policy": {
    "protected_branches": true,
    "custom_branch_policies": false
  },
  "reviewers": [
    { "type": "User", "id": $ADMIN_ID }
  ]
}
JSON
echo "  ✓ prod hardened"

echo
echo "Done. Next steps (manual; values are sensitive):"
cat <<'EOM'

  Add per-environment secrets via:
    Settings → Environments → <env> → Add secret

  Required secrets (all 3 envs):
    AZURE_CLIENT_ID         federated identity client ID
    AZURE_TENANT_ID         Azure AD tenant ID
    AZURE_SUBSCRIPTION_ID   target subscription ID

  Federated credential setup in Azure (run once per env):

    RG=lvlswp-tfstate-dev-rg               # adjust per env
    az identity create -n level-sweep-agent-gha-dev -g $RG -l eastus
    CLIENT_ID=$(az identity show -n level-sweep-agent-gha-dev -g $RG --query clientId -o tsv)

    az identity federated-credential create -n gha-main \
      -g $RG --identity-name level-sweep-agent-gha-dev \
      --issuer https://token.actions.githubusercontent.com \
      --subject "repo:codesidh/level-sweep-agent:environment:dev" \
      --audience api://AzureADTokenExchange

    az role assignment create \
      --assignee $CLIENT_ID \
      --role Contributor \
      --scope /subscriptions/$AZURE_SUBSCRIPTION_ID

  Repeat for stage and prod (use environment:stage and environment:prod
  in the federated credential subject).
EOM
