把 IBM Plex Mono 字体放在这个目录。

需要的文件（文件名必须一致，代码按这个名字引用）：
    ibm_plex_mono_regular.ttf

来源：https://github.com/IBM/plex （SIL OFL 1.1，见工程根 LICENSES/OFL-IBM-Plex-Mono.txt）

缺失时的行为：
    代码通过 Type.kt 的 monoFamilyOrNull() 探测 res/font/ibm_plex_mono_regular，
    取不到就回退 FontFamily.Monospace（系统等宽），不会崩溃、不会阻塞构建。
    所以缺文件时仍可正常 Sync / assembleDebug。
