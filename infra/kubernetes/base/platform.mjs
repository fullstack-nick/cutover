// Reviewable Kubernetes resources. Secrets and machine addresses are supplied only by the local renderer.
export const namespaces = { apps: 'cutover-apps', platform: 'cutover-platform', observability: 'cutover-observability' };
const labels = name => ({ 'app.kubernetes.io/name': name, 'app.kubernetes.io/part-of': 'cutover' });
const metadata = (name, namespace) => ({ name, namespace, labels: labels(name) });
export const literal = (name, value) => ({ name, value: String(value) });
export const secret = (name, key, credential) => ({ name, valueFrom: { secretKeyRef: { name: credential, key } } });
export const config = (name, namespace, data) => ({ apiVersion: 'v1', kind: 'ConfigMap', metadata: metadata(name, namespace), data });
export const service = (name, namespace, ports) => ({ apiVersion: 'v1', kind: 'Service', metadata: metadata(name, namespace), spec: { selector: labels(name), ports: ports.map(([name, port]) => ({ name, port, targetPort: port })) } });
const health = (port, path = '/actuator/health/readiness') => ({ httpGet: { port, path }, periodSeconds: 5, timeoutSeconds: 3, failureThreshold: 24 });
const mount = (name, mountPath, subPath) => ({ name, mountPath, ...(subPath ? { subPath } : {}), readOnly: true });
const configuration = name => ({ name, configMap: { name } });
const credential = name => ({ name, secret: { secretName: name, defaultMode: 292 } });
function workload(name, namespace, image, { env = [], args, command, memory = '768Mi', cpu = '1500m', port = 8080, readiness, mounts = [], volumes = [], user = 10001, readOnly = true, storage, extra = {} } = {}) {
  const pod = { automountServiceAccountToken: false, terminationGracePeriodSeconds: 30,
    securityContext: { runAsNonRoot: true, runAsUser: user, runAsGroup: user, fsGroup: user, seccompProfile: { type: 'RuntimeDefault' } },
    containers: [{ name, image, imagePullPolicy: 'Never', env, ...(args ? { args } : {}), ...(command ? { command } : {}),
      ports: [{ containerPort: port }], resources: { requests: { cpu: '100m', memory: memory === '96Mi' ? '32Mi' : '128Mi' }, limits: { cpu, memory } },
      securityContext: { allowPrivilegeEscalation: false, readOnlyRootFilesystem: readOnly, capabilities: { drop: ['ALL'] } },
      readinessProbe: readiness ?? health(9091),
      volumeMounts: [{ name: 'tmp', mountPath: '/tmp' }, ...mounts, ...(storage ? [{ name: 'data', mountPath: storage.path }] : [])] }],
    volumes: [{ name: 'tmp', emptyDir: { medium: 'Memory', sizeLimit: '64Mi' } }, ...volumes], ...extra };
  const spec = { replicas: 1, selector: { matchLabels: labels(name) }, template: { metadata: { labels: labels(name) }, spec: pod } };
  if (storage) {
    spec.serviceName = name;
    spec.volumeClaimTemplates = [{ metadata: { name: 'data' }, spec: { accessModes: ['ReadWriteOnce'], storageClassName: 'standard', resources: { requests: { storage: storage.size } } } }];
  } else spec.strategy = { type: 'Recreate' };
  return { apiVersion: 'apps/v1', kind: storage ? 'StatefulSet' : 'Deployment', metadata: metadata(name, namespace), spec };
}
export function platformResources(images, settings) {
  const { apps, platform, observability } = namespaces;
  const result = Object.values(namespaces).map(name => ({ apiVersion: 'v1', kind: 'Namespace', metadata: { name, labels: { 'app.kubernetes.io/part-of': 'cutover', 'pod-security.kubernetes.io/enforce': 'restricted' } } }));
  const add = (...objects) => result.push(...objects);
  add(service('application-db', platform, [['postgres', 5432]]), workload('application-db', platform, images.postgres, {
    user: 999, readOnly: false, port: 5432, memory: '1Gi', cpu: '2000m',
    env: [secret('POSTGRES_PASSWORD', 'password', 'database-admin'), literal('POSTGRES_INITDB_ARGS', '--auth-host=scram-sha-256')],
    args: ['postgres', '-c', 'shared_buffers=256MB', '-c', 'max_connections=100', '-c', 'timezone=UTC', '-c', 'track_wal_io_timing=on'],
    readiness: { exec: { command: ['pg_isready', '-U', 'postgres'] }, periodSeconds: 3 },
    storage: { path: '/var/lib/postgresql', size: '3Gi' }, volumes: [credential('database-init')], mounts: [mount('database-init', '/docker-entrypoint-initdb.d/010-owners.sql', 'init.sql')],
  }));
  const databasePod = result.find(item => item.kind === 'StatefulSet' && item.metadata.name === 'application-db').spec.template.spec;
  result.find(item => item.kind === 'StatefulSet' && item.metadata.name === 'application-db').spec.template.metadata.annotations = { 'kubectl.kubernetes.io/default-container': 'application-db' };
  databasePod.volumes.push(configuration('volume-probe'), { name: 'volume-observation', emptyDir: { medium: 'Memory', sizeLimit: '1Mi' } });
  databasePod.containers[0].volumeMounts.push(mount('volume-observation', '/run/cutover-volume'));
  databasePod.containers.push({ name: 'volume-probe', image: images.postgres, imagePullPolicy: 'Never', command: ['sh', '/etc/cutover/volume-probe.sh'],
    resources: { requests: { cpu: '5m', memory: '8Mi' }, limits: { cpu: '100m', memory: '32Mi' } },
    securityContext: { allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: { drop: ['ALL'] } },
    volumeMounts: [mount('data', '/var/lib/postgresql'), mount('volume-probe', '/etc/cutover'), { name: 'volume-observation', mountPath: '/run/cutover-volume' }] });
  add(service('rabbitmq', platform, [['amqp', 5672], ['metrics', 15692]]), workload('rabbitmq', platform, images.rabbitmq, {
    user: 999, readOnly: false, port: 5672, memory: '512Mi', cpu: '1000m', storage: { path: '/var/lib/rabbitmq', size: '1Gi' },
    env: [literal('RABBITMQ_NODENAME', 'rabbit@rabbitmq-0'), literal('RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS', '+S 2:2'), literal('RABBITMQ_ENABLED_PLUGINS_FILE', '/etc/rabbitmq/enabled_plugins')],
    // Periodic CLI probes join Erlang distribution and compete with message processing.
    readiness: { tcpSocket: { port: 5672 }, periodSeconds: 5, timeoutSeconds: 2, failureThreshold: 6 },
    volumes: [configuration('rabbit-config'), credential('rabbit-definitions')],
    mounts: [mount('rabbit-config', '/etc/rabbitmq/rabbitmq.conf', 'rabbitmq.conf'), mount('rabbit-config', '/etc/rabbitmq/enabled_plugins', 'enabled_plugins'), mount('rabbit-definitions', '/etc/rabbitmq/definitions.json', 'definitions.json')],
  }));
  const identityEnv = [literal('KC_DB_URL', `jdbc:postgresql://application-db:5432/cutover_keycloak`), literal('KC_DB_USERNAME', 'cutover_keycloak_runtime'), secret('KC_DB_PASSWORD', 'password', 'keycloak-runtime'),
    literal('KC_HOSTNAME', 'http://localhost:8780/identity'), literal('KC_HTTP_ENABLED', 'true'), literal('KC_PROXY_HEADERS', 'xforwarded'), literal('KC_HTTP_RELATIVE_PATH', '/identity'), literal('KC_HTTP_MANAGEMENT_RELATIVE_PATH', '/'), literal('JAVA_OPTS_KC_HEAP', '-XX:MaxRAMPercentage=60 -XX:InitialRAMPercentage=20')];
  add(service('keycloak', platform, [['http', 8080], ['management', 9000]]), workload('keycloak', platform, images.keycloak, {
    user: 1000, readOnly: false, memory: '1Gi', cpu: '2000m', env: identityEnv, args: ['start', '--optimized'], readiness: health(9000, '/health/ready'),
  }));
  const common = [literal('CUTOVER_MIGRATIONS_ENABLED', 'false'), literal('CUTOVER_TEST_CONTROLS_ENABLED', 'true'), literal('CUTOVER_ISSUER', 'http://localhost:8780/identity/realms/cutover'),
    literal('CUTOVER_JWKS_URI', `http://keycloak.${platform}.svc.cluster.local:8080/identity/realms/cutover/protocol/openid-connect/certs`), literal('CUTOVER_TOKEN_URI', `http://keycloak.${platform}.svc.cluster.local:8080/identity/realms/cutover/protocol/openid-connect/token`),
    literal('SPRING_RABBITMQ_HOST', `rabbitmq.${platform}.svc.cluster.local`), literal('SPRING_RABBITMQ_VIRTUAL_HOST', 'cutover'),
    literal('JAVA_TOOL_OPTIONS', '-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Dorg.jooq.no-logo=true -Dorg.jooq.no-tips=true -javaagent:/app/telemetry.jar'),
    literal('OTEL_EXPORTER_OTLP_ENDPOINT', `http://collector.${observability}.svc.cluster.local:4318`), literal('OTEL_EXPORTER_OTLP_PROTOCOL', 'http/protobuf'),
    literal('OTEL_METRICS_EXPORTER', 'none'), literal('OTEL_LOGS_EXPORTER', 'none'), literal('OTEL_BSP_MAX_QUEUE_SIZE', '512'), literal('OTEL_BSP_MAX_EXPORT_BATCH_SIZE', '128'), literal('OTEL_BSP_EXPORT_TIMEOUT', '2000'), literal('OTEL_EXPORTER_OTLP_TIMEOUT', '2000')];
  for (const [name, owner] of [['legacy-core', 'core'], ['equipment-adapter', 'adapter'], ['execution-service', 'execution'], ['shadow-scheduler', 'shadow'], ['returns-service', 'returns']]) {
    const env = [...common, literal('OTEL_SERVICE_NAME', name), literal('CUTOVER_DATABASE_URL', `jdbc:postgresql://application-db.${platform}.svc.cluster.local:5432/cutover_${owner}`), literal('CUTOVER_DATABASE_USER', `cutover_${owner}_runtime`), secret('CUTOVER_DATABASE_PASSWORD', 'databasePassword', `${name}-runtime`),
      literal('SPRING_RABBITMQ_USERNAME', `cutover_${owner}`), secret('SPRING_RABBITMQ_PASSWORD', 'brokerPassword', `${name}-runtime`)];
    const extra = {};
    if (name !== 'equipment-adapter') env.push(literal('CUTOVER_ADAPTER_URL', 'http://equipment-adapter:8080'), secret('CUTOVER_CLIENT_SECRET', 'clientSecret', `${name}-runtime`), literal('CUTOVER_CLIENT_ID', name), literal('CUTOVER_SERVICE_NAME', name), literal('CUTOVER_SHADOW_MODE', name === 'shadow-scheduler'));
    else {
      env.push(literal('CUTOVER_CORE_URL', 'http://legacy-core:8080'), literal('CUTOVER_EXECUTION_URL', 'http://execution-service:8080'), secret('CUTOVER_CLIENT_SECRET', 'clientSecret', `${name}-runtime`));
      env.push(literal('CUTOVER_EQUIPMENT_URL', `https://equipment-simulator.${platform}.svc.cluster.local:8443`), literal('CUTOVER_ADAPTER_KEY_STORE', '/etc/cutover/adapter.p12'), literal('CUTOVER_EQUIPMENT_TRUST_STORE', '/etc/cutover/trust.p12'), secret('CUTOVER_EQUIPMENT_STORE_PASSWORD', 'password', 'adapter-equipment'));
      extra.volumes = [credential('adapter-equipment')]; extra.mounts = [mount('adapter-equipment', '/etc/cutover')];
    }
    add(service(name, apps, [['http', 8080], ['management', 9091]]), workload(name, apps, images[name === 'shadow-scheduler' ? 'execution-service' : name], { env, ...extra }));
    const process = result.at(-1).spec.template.spec.containers[0];
    process.startupProbe = { ...health(9091, '/actuator/health/liveness'), periodSeconds: 3, failureThreshold: 60 };
    process.livenessProbe = { ...health(9091, '/actuator/health/liveness'), periodSeconds: 15, failureThreshold: 3 };
  }
  const simulatorService = service('equipment-simulator', platform, [['https', 8443], ['management', 9091]]); delete simulatorService.spec.selector;
  add(simulatorService, { apiVersion: 'discovery.k8s.io/v1', kind: 'EndpointSlice', metadata: { ...metadata('equipment-simulator', platform), labels: { ...labels('equipment-simulator'), 'kubernetes.io/service-name': 'equipment-simulator', 'endpointslice.kubernetes.io/managed-by': 'cutover-renderer' } }, addressType: 'IPv4', ports: [{ name: 'https', port: 8443, protocol: 'TCP' }, { name: 'management', port: 9091, protocol: 'TCP' }], endpoints: [{ addresses: [settings.simulatorAddress], conditions: { ready: true } }] });
  add(service('proxy', apps, [['http', 8080]]), workload('proxy', apps, images['operations-console'], {
    user: 101, memory: '96Mi', cpu: '500m', readiness: health(8080, '/healthz'), volumes: [configuration('proxy-config')], mounts: [mount('proxy-config', '/etc/nginx/conf.d/default.conf', 'default.conf')], env: [literal('NGINX_WORKER_PROCESSES', 2)],
  }));
  for (const [name, image, port, args, user, memory, storage, readyPath] of [
    ['collector', 'collector', 4318, ['--config=/etc/cutover/config.yaml'], 10001, '384Mi', null, '/'],
    ['prometheus', 'prometheus', 9090, ['--config.file=/etc/cutover/config.yaml', '--storage.tsdb.path=/data', '--storage.tsdb.retention.time=24h', '--storage.tsdb.retention.size=512MB'], 65534, '384Mi', { path: '/data', size: '1Gi' }, '/-/ready'],
    ['tempo', 'tempo', 3200, ['-config.file=/etc/cutover/config.yaml', '-target=all', '-backend-scheduler.provider.work.compaction.block-retention=24h'], 10001, '1Gi', { path: '/var/tempo', size: '1Gi' }, '/ready'],
    ['grafana', 'grafana', 3000, undefined, 472, '1Gi', { path: '/var/lib/grafana', size: '1Gi' }, '/api/health'],
  ]) {
    const extra = name === 'grafana' ? {
      env: [literal('GOMEMLIMIT', '768MiB'), literal('GF_ANALYTICS_REPORTING_ENABLED', false), literal('GF_ANALYTICS_CHECK_FOR_UPDATES', false), literal('GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES', false), literal('GF_NEWS_NEWS_FEED_ENABLED', false), literal('GF_SECURITY_ADMIN_USER', 'cutover-admin'), secret('GF_SECURITY_ADMIN_PASSWORD', 'password', 'grafana-admin'), literal('GF_USERS_ALLOW_SIGN_UP', false), literal('GF_PLUGINS_PREINSTALL_DISABLED', true)],
      volumes: [configuration('grafana-datasources'), configuration('grafana-dashboards'), configuration('cutover-dashboard')], mounts: [mount('grafana-datasources', '/etc/grafana/provisioning/datasources'), mount('grafana-dashboards', '/etc/grafana/provisioning/dashboards'), mount('cutover-dashboard', '/var/lib/grafana/dashboards')],
    } : { volumes: [configuration(`${name}-config`)], mounts: [mount(`${name}-config`, '/etc/cutover')] };
    if (name === 'tempo') extra.env = [literal('GOMEMLIMIT', '768MiB')];
    add(service(name, observability, [[name === 'collector' ? 'otlp-http' : 'http', port], ...(name === 'collector' ? [['health', 13133], ['metrics', 8888]] : name === 'tempo' ? [['otlp-grpc', 4317], ['otlp-http', 4318]] : [])]),
      workload(name, observability, images[image], { user, memory, cpu: '1000m', args, port, storage, readiness: health(name === 'collector' ? 13133 : port, readyPath), ...extra }));
  }
  return result;
}
export function migrationJob(name, owner, image, schema = name, target = 'latest') {
  return { apiVersion: 'batch/v1', kind: 'Job', metadata: metadata(`migrate-${name}`, namespaces.apps), spec: { backoffLimit: 0, activeDeadlineSeconds: 180, ttlSecondsAfterFinished: 86400,
    template: { metadata: { labels: labels(`migrate-${name}`) }, spec: { restartPolicy: 'Never', automountServiceAccountToken: false, securityContext: { runAsNonRoot: true, runAsUser: 10001, runAsGroup: 10001, seccompProfile: { type: 'RuntimeDefault' } },
      containers: [{ name: 'migrate', image, imagePullPolicy: 'Never', securityContext: { allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: { drop: ['ALL'] } }, resources: { requests: { memory: '128Mi', cpu: '100m' }, limits: { memory: '512Mi', cpu: '1000m' } },
        command: ['java', '-Dloader.main=dev.cutover.platform.MigrationMain', '-cp', '/app/app.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher'],
        env: [literal('CUTOVER_DATABASE_URL', `jdbc:postgresql://application-db.${namespaces.platform}.svc.cluster.local:5432/cutover_${owner}`), literal('CUTOVER_DATABASE_USER', `cutover_${owner}_migrator`), secret('CUTOVER_DATABASE_PASSWORD', 'password', `${name}-migrator`), literal('CUTOVER_MIGRATION_LOCATIONS', `classpath:db/platform,classpath:db/${schema}`), literal('CUTOVER_MIGRATION_TARGET', target)] }] } } } };
}
export function identityMigrationJob(image) {
  const env = [literal('KC_DB_URL', 'jdbc:postgresql://application-db:5432/cutover_keycloak'), literal('KC_DB_USERNAME', 'cutover_keycloak_migrator'), secret('KC_DB_PASSWORD', 'password', 'keycloak-migrator'),
    literal('KC_HOSTNAME', 'http://localhost:8780/identity'), literal('KC_HTTP_ENABLED', true), literal('KC_PROXY_HEADERS', 'xforwarded'), literal('KC_BOOTSTRAP_ADMIN_USERNAME', 'bootstrap-admin'), secret('KC_BOOTSTRAP_ADMIN_PASSWORD', 'password', 'identity-admin'), literal('JAVA_OPTS_KC_HEAP', '-XX:MaxRAMPercentage=60 -XX:InitialRAMPercentage=20')];
  return { apiVersion: 'batch/v1', kind: 'Job', metadata: metadata('migrate-keycloak', namespaces.platform), spec: { backoffLimit: 0, activeDeadlineSeconds: 240, ttlSecondsAfterFinished: 86400,
    template: { metadata: { labels: labels('migrate-keycloak') }, spec: { restartPolicy: 'Never', automountServiceAccountToken: false, securityContext: { runAsNonRoot: true, runAsUser: 1000, runAsGroup: 1000, seccompProfile: { type: 'RuntimeDefault' } }, volumes: [credential('identity-realm')],
      containers: [{ name: 'migrate', image, imagePullPolicy: 'Never', env, securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: ['ALL'] } }, resources: { requests: { memory: '256Mi', cpu: '100m' }, limits: { memory: '1Gi', cpu: '2000m' } }, volumeMounts: [mount('identity-realm', '/opt/keycloak/data/import')],
        command: ['bash', '-c', '/opt/keycloak/bin/kc.sh start --optimized --import-realm & worker=$!; trap "kill -TERM $worker 2>/dev/null || true" EXIT; for attempt in {1..150}; do if (exec 3<>/dev/tcp/127.0.0.1/8080; printf "GET /identity/realms/cutover HTTP/1.0\\r\\n\\r\\n" >&3; IFS= read -r status <&3; [[ "$status" == *" 200 "* ]]) 2>/dev/null; then kill -TERM $worker; wait $worker; exit 0; fi; kill -0 $worker 2>/dev/null || exit 1; sleep 1; done; exit 1'] }] } } } };
}
