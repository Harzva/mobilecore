# Termux P0 安装命令说明

> 说明：这里是人工执行命令说明，不做全自动强制安装。原因是不同手机、Termux 版本、包源状态差异较大，P0 阶段应保留人工确认。

## 1. 基础包

```bash
pkg update
pkg upgrade
pkg install git curl jq clang cmake make python
```

## 2. 优先尝试安装 llama-cpp

```bash
pkg search llama
pkg install llama-cpp
```

如果安装后存在：

```bash
which llama-server
which llama-cli
```

即可进入启动阶段。

## 3. 如果没有 llama-cpp 包

手动源码编译：

```bash
cd ~
git clone https://github.com/ggml-org/llama.cpp.git
cd llama.cpp
cmake -B build
cmake --build build -j4
```

编译后检查：

```bash
ls ~/llama.cpp/build/bin/llama-server
ls ~/llama.cpp/build/bin/llama-cli
```

## 4. 模型目录

```bash
mkdir -p ~/mobilecore/models
```

把 GGUF 模型放入：

```text
~/mobilecore/models/
```

## 5. 启动服务

```bash
cd <MobileCore-P0-Probe/scripts所在目录>
chmod +x *.sh
./start_llama_server.sh ~/mobilecore/models/your-model.gguf
```

## 6. 测试 API

另开一个 Termux session：

```bash
cd <MobileCore-P0-Probe/scripts所在目录>
BASE_URL=http://127.0.0.1:8080/v1 MODEL=local-model ./test_openai_compatible_api.sh
```

## 7. 快速跑分

```bash
BASE_URL=http://127.0.0.1:8080/v1 MODEL=local-model ./benchmark_quick.sh
```

## 8. MobileCode 配置

```text
Provider: MobileCore P0
Type: OpenAI-compatible
Base URL: http://127.0.0.1:8080/v1
API Key: local
Model: local-model
```
