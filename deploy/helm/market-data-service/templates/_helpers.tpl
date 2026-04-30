{{/*
Common helpers for the market-data-service chart.
*/}}

{{/*
Chart name (truncated to 63 chars for K8s label compliance).
*/}}
{{- define "market-data-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name. If release name already contains the chart name,
don't double it up.
*/}}
{{- define "market-data-service.fullname" -}}
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
{{- define "market-data-service.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common labels applied to every resource.
*/}}
{{- define "market-data-service.labels" -}}
helm.sh/chart: {{ include "market-data-service.chart" . }}
{{ include "market-data-service.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: levelsweep
{{- end -}}

{{/*
Selector labels — used in matchLabels (must NOT contain version).
*/}}
{{- define "market-data-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "market-data-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
Resolved ServiceAccount name.
*/}}
{{- define "market-data-service.serviceAccountName" -}}
{{- default (include "market-data-service.fullname" .) .Values.serviceAccount.name -}}
{{- end -}}

{{/*
Synced K8s secret name for a given KV object. Truncated to 63 chars.
Usage: include "market-data-service.kvSecretName" (dict "root" . "object" .name)
*/}}
{{- define "market-data-service.kvSecretName" -}}
{{- printf "%s-%s" (include "market-data-service.fullname" .root) .object | trunc 63 | trimSuffix "-" -}}
{{- end -}}
