# Netty Virtual Thread Scheduler Benchmark

Benchmarks Infinispan server with non-clustered Hyperfoil.

## Setup

Run the setup script to fetch the AWS provisioning role and the Hyperfoil jbang-catalog:

```bash
./setup.bash
```

This downloads the `aws_ec2` Ansible role from [IspnPerfTest](https://github.com/jgroups-extras/IspnPerfTest)
and clones the [jbang-catalog](https://github.com/diegolovison/jbang-catalog) (branch `hotrod`).

Install the Ansible dependencies:

```bash
ansible-galaxy collection install -r roles/ec2-requirements.yml
pip3 install --user boto3 botocore
```

## Provision EC2 instances

```bash
ansible-playbook aws-ec2.yml \
  -e region=sa-east-1 \
  -e operation=create \
  -e test_cluster_size=6 \
  -e test_instance_type=tg4.2xlarge
```

This creates EC2 instances, a security group, a key pair, and generates an inventory file
(`benchmark_<user>_<region>_inventory.yaml`). The instances have a 2-hour self-destruct timer.

## Deploy

Build the Infinispan server distribution locally. The `ispn_dist` variable must point to the
extracted server directory, for example:

```
infinispan/server/runtime/target/infinispan-server-16.2.0-SNAPSHOT
```

The deploy playbook handles all server configuration automatically:
- Replaces `infinispan.xml` with a benchmark configuration (TCPPING discovery, `benchmark-cache`)
- Generates `hosts.txt` for cluster discovery
- Creates the `admin` user (`admin:password`) on each server node
- Renders the Hyperfoil benchmark definition with the correct server address

The first run on new instances requires `-e setup=true` to install system packages, JDK,
JBang, and the Loom JDK:

```bash
ansible-playbook -i inventory.yaml deploy.yml \
  -e ispn_dist=/path/to/infinispan-server-16.2.0-SNAPSHOT \
  -e setup=true
```

Subsequent deploys (e.g., after rebuilding the server) can skip the setup:

```bash
ansible-playbook -i inventory.yaml deploy.yml \
  -e ispn_dist=/path/to/infinispan-server-16.2.0-SNAPSHOT
```

## Run the benchmark

```bash
ansible-playbook -i inventory.yaml run.yml
```

Options:
- `jfr=true` (default) — enable JFR recording on server nodes. Pass `-e jfr=false` to disable
- `loom=true` — use the Loom JDK with the custom Netty scheduler (`-Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler`). Pass `-e loom=true`, disabled by default.

The playbook starts the servers with monitoring (pidstat, mpstat, perf stat), waits for the
cluster to form, runs the Hyperfoil benchmark from the controller node, stops everything,
and downloads results to `data/<YYYYMMDD>/<HHMM>/`.

## Analyze results

```bash
jbang BenchmarkSummary.java --data data/20260601/1421 --run 0001
```

- `--data` — path to a run folder containing per-node directories with pidstat.log, perf-stat.txt, mpstat.log, and .jfr files
- `--run` — Hyperfoil run ID (resolves to `data/<date>/hyperfoil/run/<ID>/stats/total.csv`)

To compare CPU/Memory usage of two runs side by side:

```bash
jbang BenchmarkSummary.java --compare data/20260601/1421 data/20260601/1530
```

## Tear down

Delete the EC2 resources:

```bash
ansible-playbook aws-ec2.yml -e operation=delete -e region=sa-east-1
```

Clean up locally generated files (roles, jbang-catalog, aws-ec2.yml):

```bash
./setup.bash --clean
```

