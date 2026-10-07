# 放置 iData UHF SDK 原生库

本工程通过 `implementation files('libs/idata-uhf-sdk.jar')` 引用 iData 官方 UHF SDK。
编译前请把厂商提供的文件放到对应位置：

```
app/libs/idata-uhf-sdk.jar                 <- UHF SDK 的 jar 包
app/src/main/jniLibs/armeabi-v7a/*.so      <- 对应 ABI 的 native 库（常见 arm64-v8a / armeabi-v7a）
app/src/main/jniLibs/arm64-v8a/*.so
```

## 获取 SDK

- 厂商开发者站点：`https://developer.idata.com.cn/` （或联系 iData 技术支持 / sales@idataglobal.com）
- 索要「T1 UHF RFID SDK」（Android，jar + so）。

## 接口对齐

`IdataUhfAdapter.java` 中已经按 iData UHF SDK 通用参数语义封装（bank 0=RESERVED/1=EPC/2=TID/3=USER、
bit/word 寻址、默认访问密码 00000000、writeTagWithFilter 过滤写）。请把里面标注 `TODO-SDK` 处的
**类名 / 方法名 / 回调形态** 替换为你拿到的 SDK jar 中的实际签名，其余业务代码无需改动。
