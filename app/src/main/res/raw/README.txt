把特殊关注提醒音放在这个目录。

需要的文件（文件名必须一致，代码按这个名字引用）：
    alert_priority.mp3

缺失时的行为：
    代码通过 res/raw 资源 id 探测，取不到就回退到系统通知音
    （RingtoneManager.TYPE_NOTIFICATION），不会崩溃。
