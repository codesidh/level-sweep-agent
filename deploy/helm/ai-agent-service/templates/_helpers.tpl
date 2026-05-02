{{/*
Common helpers for the ai-agent-service chart.
*/}}

{{/*
Chart name (truncated to 63 chars for K8s label compliance).
*/}}
{{- define "ai-agent-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name. If release name already contains the chart name,
don't double it up.
*/}}
{{- define "ai-agent-service.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Chart label (chart-name + version, dot-replaced for label safety).
*/}}
{{- define "ai-agent-service.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common labels applied to every resource.
*/}}
{{- define "ai-agent-service.labels" -}}
helm.sh/chart: {{ include "ai-agent-service.chart" . }}
{{ include "ai-agent-service.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: levelsweep
{{- end -}}

{{/*
Selector labels — used in matchLabels (must NOT contain version).
*/}}
{{- define "ai-agent-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "ai-agent-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
Resolved ServiceAccount name.
*/}}
{{- define "ai-agent-service.serviceAccountName" -}}
{{- default (include "ai-agent-service.fullname" .) .Values.serviceAccount.name -}}
{{- end -}}

{{/*
Synced K8s secret name for a given KV object. Truncated to 63 chars.
Usage: include "ai-agent-service.kvSecretName" (dict "root" . "object" .name)
*/}}
{{- define "ai-agent-service.kvSecretName" -}}
{{- printf "%s-%s" (include "ai-agent-service.fullname" .root) .object | trunc 63 | trimSuffix "-" -}}
{{- end -}}
