{{/*
Common helpers for the decision-engine chart.
*/}}

{{/*
Chart name (truncated to 63 chars for K8s label compliance).
*/}}
{{- define "decision-engine.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fully qualified app name. If release name already contains the chart name,
don't double it up.
*/}}
{{- define "decision-engine.fullname" -}}
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
{{- define "decision-engine.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common labels applied to every resource.
*/}}
{{- define "decision-engine.labels" -}}
helm.sh/chart: {{ include "decision-engine.chart" . }}
{{ include "decision-engine.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: levelsweep
{{- end -}}

{{/*
Selector labels — used in matchLabels (must NOT contain version).
*/}}
{{- define "decision-engine.selectorLabels" -}}
app.kubernetes.io/name: {{ include "decision-engine.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
Resolved ServiceAccount name.
*/}}
{{- define "decision-engine.serviceAccountName" -}}
{{- default (include "decision-engine.fullname" .) .Values.serviceAccount.name -}}
{{- end -}}

{{/*
Synced K8s secret name for a given KV object. Truncated to 63 chars.
Usage: include "decision-engine.kvSecretName" (dict "root" . "object" .name)
*/}}
{{- define "decision-engine.kvSecretName" -}}
{{- printf "%s-%s" (include "decision-engine.fullname" .root) .object | trunc 63 | trimSuffix "-" -}}
{{- end -}}
