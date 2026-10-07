# 放置 iData UHF SDK 的原生库（.so）

iData T1 UHF 的 UHF 能力由厂商提供的 native 库提供。请把 SDK 包里的 `.so` 按 ABI 放到这里：

```
app/src/main/jniLibs/arm64-v8a/libidatauhf.so      <- 64 位（T1 UHF 多为 64 位，优先）
app/src/main/jniLibs/armeabi-v7a/libidatauhf.so    <- 32 位（兼容）
```

> 文件名 `libidatauhf.so` 仅为示例。真实文件名以你拿到的 SDK 包为准（可能是
> `libuhf.so` / `libidata_rfid.so` 等）。若 jar 里已内嵌 so 或走 AAR，则无需手动放。

缺少此目录或对应 so 时，打包会报 `UnsatisfiedLinkError`（运行时）或链接失败。
获取方式：厂商开发者站 `developer.idata.com.cn` 或联系 iData 技术支持 / `sales@idataglobal.com`，
索要「T1 UHF RFID SDK（Android，jar + so）」。
