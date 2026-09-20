# 构建一个code agent的安卓应用

## 架构设计
1. 底层是类似termux的shell环境，再加proot-distro实现debian linux shell环境
2. 在linux shell上面安装cusor-cli、copiloit-cli、ClaudeCode cli、Codex cli等code agent系统
3. 在这之上是UI环境，参考一般的code agent UI即可
    1. 上面可切换不同的code agent系统
    2. 右侧显示历史会话，选中则进去历史会话，也可新建新的会话
    3. 新的会话一开始需要选择或者创建工作目录
    4. 也存在按钮直接打开shell环境，通过命令行进行操作，交互形式参考termux，shell环境存在返回按钮，返回到会话窗口
    5. 会话窗口支持外部文件导入，或者直接@工作目录内部文件
    6. UI完全通过对应code agent来实现功能，只是负责界面展示

## 要求
1. android应用能编译成apk，在自己手机上安装即可，暂不需要上架应用商店
2. 保持尽量简单，不要过设计，但功能要完备