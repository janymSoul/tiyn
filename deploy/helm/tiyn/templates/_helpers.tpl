{{- define "tiyn.name" -}}
{{- .Release.Name | trunc 40 | trimSuffix "-" -}}
{{- end -}}

{{- define "tiyn.labels" -}}
app.kubernetes.io/name: tiyn
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "tiyn.selector" -}}
app.kubernetes.io/name: tiyn
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "tiyn.secretName" -}}
{{- if .Values.secret.existingSecret -}}
{{- .Values.secret.existingSecret -}}
{{- else -}}
{{- printf "%s-secrets" (include "tiyn.name" .) -}}
{{- end -}}
{{- end -}}
