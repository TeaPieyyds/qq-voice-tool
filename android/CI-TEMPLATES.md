# 其他 CI 平台配置模板

所有平台的关键就一句话：**把 `SIGN_KEY_B64` 和 `SIGN_CERT_B64` 两个环境变量传进去**，
然后调 `bash android/build.sh`。脚本自己解码、签名、校验指纹。

生成这两个值（本地跑一次）：

```bash
base64 -w0 Rinkai.pk8      | tr -d '\n' > key.b64
base64 -w0 Rinkai.x509.pem | tr -d '\n' > cert.b64
```

---

## GitLab CI

`.gitlab-ci.yml`：

```yaml
stages: [build]

apk:
  stage: build
  image: eclipse-temurin:17-jdk

  variables:
    ANDROID_SDK_ROOT: /opt/android-sdk
    ANDROID_HOME: /opt/android-sdk

  before_script:
    - apt-get update -qq && apt-get install -y -qq wget unzip python3
    # Android SDK（cmdline-tools）
    - mkdir -p $ANDROID_SDK_ROOT/cmdline-tools
    - wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    - unzip -q commandlinetools-linux-*.zip -d $ANDROID_SDK_ROOT/cmdline-tools
    - mv $ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools $ANDROID_SDK_ROOT/cmdline-tools/latest
    - export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools
    - yes | sdkmanager --licenses > /dev/null
    - sdkmanager "platforms;android-34" "build-tools;34.0.0"
    - cd android && bash fetch-libs.sh && cd ..

  script:
    - cd android
    - bash build.sh            # 自动读 SIGN_KEY_B64 / SIGN_CERT_B64
    - cd ..
    - ls -lh android/out/app-signed.apk

  artifacts:
    paths:
      - android/out/app-signed.apk
    expire_in: 30 days

  # 在 Settings → CI/CD → Variables 里配置，勾 Masked：
  #   SIGN_KEY_B64
  #   SIGN_CERT_B64
```

---

## Gitea Actions

Gitea 的 Actions 语法兼容 GitHub，直接复制 `.github/workflows/build.yml` 到
`.gitea/workflows/build.yml`。

密钥配置：仓库 → `设置` → `Actions` → `Secrets`，加 `SIGN_KEY_B64` / `SIGN_CERT_B64`。

注意 Gitea 的 `runs-on` 需要写具体标签，比如 `runs-on: ubuntu-latest` 要换成
你 runner 注册时的标签（常见是 `ubuntu-latest` 或 `docker`）。

---

## Woodpecker CI

`.woodpecker.yml`：

```yaml
pipeline:
  build:
    image: eclipse-temurin:17-jdk
    environment:
      - ANDROID_SDK_ROOT=/opt/android-sdk
      - ANDROID_HOME=/opt/android-sdk
    commands:
      - apt-get update -qq && apt-get install -y -qq wget unzip python3
      - mkdir -p $ANDROID_SDK_ROOT/cmdline-tools
      - wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
      - unzip -q commandlinetools-linux-*.zip -d $ANDROID_SDK_ROOT/cmdline-tools
      - mv $ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools $ANDROID_SDK_ROOT/cmdline-tools/latest
      - export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin
      - yes | sdkmanager --licenses > /dev/null
      - sdkmanager "platforms;android-34" "build-tools;34.0.0"
      - cd android && bash fetch-libs.sh && cd ..
      - cd android && bash build.sh

    secrets: [ sign_key_b64, sign_cert_b64 ]
```

Woodpecker 的 secret 名是小写，脚本读的是大写。
在 pipeline 里显式映射：

```yaml
    environment:
      SIGN_KEY_B64:
        from_secret: sign_key_b64
      SIGN_CERT_B64:
        from_secret: sign_cert_b64
```

---

## Jenkins

`Jenkinsfile`：

```groovy
pipeline {
  agent { label 'android' }
  environment {
    ANDROID_SDK_ROOT = '/opt/android-sdk'
    ANDROID_HOME     = '/opt/android-sdk'
  }
  stages {
    stage('Deps') {
      steps {
        dir('android') { sh 'bash fetch-libs.sh' }
      }
    }
    stage('Build') {
      steps {
        withCredentials([
          string(credentialsId: 'rinkai-key-b64',  variable: 'SIGN_KEY_B64'),
          string(credentialsId: 'rinkai-cert-b64', variable: 'SIGN_CERT_B64')
        ]) {
          dir('android') { sh 'bash build.sh' }
        }
      }
    }
    stage('Archive') {
      steps {
        archiveArtifacts artifacts: 'android/out/app-signed.apk', fingerprint: true
      }
    }
  }
}
```

Jenkins 里在 `Manage Jenkins → Credentials` 建两个 Secret text：
`rinkai-key-b64`、`rinkai-cert-b64`。

---

## 自建机器 / 本地

密钥就在磁盘上时，用绝对路径最省事：

```bash
export SIGN_KEY=/storage/emulated/0/MT2/keys/Rinkai.pk8
export SIGN_CERT=/storage/emulated/0/MT2/keys/Rinkai.x509.pem
cd android && bash build.sh
```

---

## 通用要点

| 项 | 说明 |
|---|---|
| JDK | 11 或更高（脚本用 `--release 11`） |
| 需要工具 | `javac` `d8` `aapt2` `zipalign` `apksigner` `python3` `curl` `unzip` |
| build-tools | 34.0.0 及以上；35/36 也能用 |
| platform | android-34 |
| 密钥必须 | 缺了就 `exit 1`，不会退回 debug 签名 |
| 指纹校验 | 签名后自动比对 SHA-256，不一致中止 |

Android SDK 里的 `aapt2` 是 x86-64 的，**aarch64 机器无法本地执行**，
所以要么用 x86-64 runner，要么走 CI 的 x86-64 环境。