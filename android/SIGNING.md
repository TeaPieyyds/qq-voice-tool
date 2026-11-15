# 签名策略（任何 CI / 本地都走这套）

本仓库**只允许用 Rinkai 签名**。构建脚本在找不到密钥时会直接 `exit 1`，
不会退回 `debug.keystore` 或临时生成密钥，避免出现「签名不一致装不上」的问题。

---

## 期望签名

```
subject        CN=茶派, C=CN
cert SHA-256   bcfb3350cf10e3524cb59714d51f0f165d5bc10391acadec67572375233ad79a
cert SHA-1     d54e0021a433f20f72f7fb36df5d19de7d69d866
cert MD5       4cea721d89bd0d75e0a6676f00cdceeb
key            RSA 2048
```

构建结束后 `build.sh` 会自动比对 SHA-256，不一致就中止。

---

## 三种提供密钥的方式

按优先级从高到低，命中一个就停。

### 方式 1：环境变量（绝对路径）

适合本地或自建 runner，密钥已经在机器上。

```bash
export SIGN_KEY=/path/to/Rinkai.pk8
export SIGN_CERT=/path/to/Rinkai.x509.pem
bash android/build.sh
```

### 方式 2：环境变量（base64）——CI 推荐

把密钥内容 base64 后放进 CI 的 Secrets，构建时解码成临时文件。

```bash
# 本地准备一次
base64 -w0 Rinkai.pk8      > key.b64
base64 -w0 Rinkai.x509.pem > cert.b64
```

然后配置：

```bash
export SIGN_KEY_B64="$(cat key.b64)"
export SIGN_CERT_B64="$(cat cert.b64)"
bash android/build.sh
```

- **GitHub Actions**：`Settings → Secrets and variables → Actions`，新建
  `SIGN_KEY_B64`、`SIGN_CERT_B64` 两个 Secret。
- **GitLab CI**：`Settings → CI/CD → Variables`，同名字段，勾选 Masked。
- **Gitea / Woodpecker / Jenkins**：等价地配成 Secret 环境变量即可。

### 方式 3：随仓库提交

`android/keys/Rinkai.pk8` + `android/keys/Rinkai.x509.pem`。
任何 clone 了仓库的 CI 都能直接构建，零配置。

**代价**：仓库是公开的话，密钥也公开了。任何人能签出同证书的 APK。

---

## 覆盖期望指纹

如果以后换了密钥，改这个环境变量即可，不用改脚本：

```bash
export SIGN_EXPECT_SHA256=<新的证书 SHA-256>
```

---

## 密钥文件格式

必须是一对 **PKCS#8 私钥 + X.509 证书**，即 `apksigner` 的 `--key` / `--cert` 参数格式：

```
Rinkai.pk8          # DER 编码的 PKCS#8 私钥
Rinkai.x509.pem     # PEM 编码的证书
```

MT2 导出的 `Rinkai.aes` 用不上，那个是 MT 管理器的加密容器。

### 如果是 keystore 格式

`keytool` 生成的 `.jks` / `.keystore` 不能直接给 `--key`。先转：

```bash
# jks -> pk8 + pem
keytool -importkeystore -srckeystore old.jks -srcstorepass 密码 \
        -destkeystore tmp.p12 -deststoretype PKCS12 -deststorepass 密码

openssl pkcs12 -in tmp.p12 -nodes -nocerts -out tmp.key -passin pass:密码
openssl pkcs8 -topk8 -nocrypt -in tmp.key -out Rinkai.pk8 -outform DER

openssl pkcs12 -in tmp.p12 -nokeys -out Rinkai.x509.pem -passin pass:密码
```

---

## 排错

| 现象 | 原因 |
|---|---|
| `✗ 找不到签名密钥` | 三种方式都没命中，检查路径或 Secret 名 |
| `✗ 签名指纹不对` | 用的不是 Rinkai，或密钥被换过 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 手机上装的是旧签名的版本，卸一次再装 |
| `base64: invalid input` | Secret 里带了换行或引号，重新 `base64 -w0` |
