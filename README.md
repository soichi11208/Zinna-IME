[English Version](README_en.md)

# zinna-IME

Android 向けオープンソース日本語 IME。変換エンジンに [mozc](https://github.com/google/mozc) を
ネイティブ組み込みし、フリック / QWERTY 入力・配列/テーマのカスタマイズに対応する。

名前とアイコンはヒャクニチソウ (百日草, *Zinnia*) から。アイコンは
`scripts/gen_launcher_icon.py` が花の記述一つからベクタとラスタの両方を生成する。

完全オフライン動作変換は端末内の `libmozc.so` と同梱辞書 `mozc.data` のみで完結する。
`AndroidManifest.xml` に `uses-permission` は一つも無く、ネットワークに出る手段が存在しない。

0.0.1〜0.0.9はOpenAlpha
0.1.0〜0.9.xはOpenBeta
1.0.0から安定とする予定

## ビルド

### 前提

- JDK 17 以上
- Python 3.12 以上 (mozc のビルドスクリプトが要求)
- [bazelisk](https://github.com/bazelbuild/bazelisk) または bazel
- Android SDK (platform 35 / build-tools 35.0.0)

### 手順

```bash
git clone --depth 1 https://github.com/google/mozc.git third_party/mozc

./scripts/build_mozc.sh

python3 scripts/gen_flick_layout.py
python3 scripts/gen_qwerty_layout.py

./scripts/fetch_dictionaries.sh

echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
```

`scripts/build_mozc.sh` は bazel を 2 回叩く。これは分けざるを得ない: `mozc.data` は
アーキ非依存のデータ blob だがホストツールで生成され、そのホストツールは
`--config oss_android` 下で incompatible とマークされているため、
1 コマンドで両方を要求すると analysis 段階で失敗する。

## 構成

```
app/       IME 本体 — InputMethodService, キーボード View, 設定画面
mozc/      mozc ネイティブのラッパ — JNI シム, 生成 protobuf, libmozc.so, mozc.data
patches/   third_party/mozc への変更。ビルド時に適用する
scripts/   ネイティブビルド、配列/アイコン生成、辞書取得
third_party/mozc/     上流の checkout (gitignore)
```

### mozc との境界

上流の `android/jni/mozcjni.cc` がエクスポートするシンボルは 1 つだけで、

```
Java_com_google_android_apps_inputmethod_libs_mozc_session_MozcJNI_initialize
```

これが `RegisterNatives` で残りのメソッドを登録する。つまり Java 側のクラス名が
`com.google.android.apps.inputmethod.libs.mozc.session.MozcJNI` に固定される。
そこで mozc を書き換える代わりに、その名前のシムクラス
(`mozc/src/main/java/.../MozcJNI.java`) をこちら側に置いた。アプリコードはシムを直接触らず、
`dev.oss.ime.mozc.MozcEngine` (ライフサイクルとスレッド安全性を持つ) を経由する。

mozc 自体への変更は `patches/` に置き、`scripts/build_mozc.sh` が適用する。
適用前に `git apply --check` するので、適用済みツリーでの再実行はエラーにならず no-op になる。
checkout はそれ以外は素のままなので、上流に追従し直せる。

| パッチ                                        | 内容                             |
| ------------------------------------------ | ------------------------------ |
| `0001-rule-based-typing-correction`        | OSS 版がスタブを積んでいる誤字修正モデルを追加      |
| `0002-android-keystore-profile-encryption` | mozc 自身のプロファイルを Keystore 鍵で暗号化 |
| `0003-trim-jni-output`                     | 応答のうちアプリ側に渡らない部分を削る            |
| `0004-left-context-particle-ranking`       | 確定済みの左文脈を候補順位に効かせる             |

### フリック入力と mozc の契約

キーボードは「かな」を送らない。mozc の `FLICK_TO_HIRAGANA` テーブルは ASCII キー入力とする対応表で (`1`→あ, `_`→い, `*`→濁点/小文字の循環)、濁点処理も小書き文字の規則も
このテーブルが実装している。かなを直接送るとそれらを全部迂回してしまうため、
配列 JSON にはキーozc に合成させる。

その対応表を手書きすると「あるフリック方向だけ無反応」という壊れ方をするので、
`scripts/gen_flick_layout.py` が上流の
`data/preedit/*.tsv` から直接生成し、値を検証している。

テーブルだけでは足りず、変換モードも合わせて切り替える必要がある。
テーブルは打鍵が何の文字になるかを決めるだけで、そこから変換をかけるかどうかは
`CompositionMode` が決める。英数プレーンを HIRAGANA のままにすると
"abc" に漢字候補が出てしまう。

| プレーン        | テーブル                                   | CompositionMode |
| ----------- | -------------------------------------- | --------------- |
| かな (フリック)   | `FLICK_TO_HIRAGANA` (13)               | `HIRAGANA`      |
| 英数 (フリック)   | `TOGGLE_FLICK_TO_HALFWIDTHASCII` (17)  | `HALF_ASCII`    |
| 記号・数字       | `TOGGLE_FLICK_TO_NUMBER` (42)          | `HIRAGANA`      |
| かな (QWERTY) | `QWERTY_MOBILE_TO_HIRAGANA` (20)       | `HIRAGANA`      |
| 英数 (QWERTY) | `QWERTY_MOBILE_TO_HALFWIDTHASCII` (22) | `HALF_ASCII`    |

同じキーを連打したときは、切り替わらず同じ文字が連続で入る。12キー面は全部の文字と記号に
フリックで届くので、mozc のトグルは「ああ」や「11」を打つときの邪魔にしかならない。
同じテーブルキーが続いたときだけ `STOP_KEY_TOGGLING` で確定させている。
濁点キーだけは対象外で、これは は→ば→ぱ の循環がそのトグル機構そのものら。

### 入力方式

かなと英字はそれぞれ独立に選べる。両方フリック、両方 QWERTY、
かなだけフリックで英字は QWERTY、の 3 通り。

レイアウト JSON は自分の系統内を指したままで、面の切替は `KeyboardStyle` が設定に従って
差し替える。記号面は意図的に差し替えない 記号面は系統の中から入るので、
QWERTY の `?123` がフリックの数字パッドを開いたら誰も頼んでいない面替えになる。

### 追加辞書

[dic-nico-intersection-pixiv](https://github.com/ncaq/dic-nico-intersection-pixiv)
(ニコニコ大百科とピクシブ百科事典の共通見出し) を標準で同梱する。あわせて
[google-ime-user-dictionary-ja-en](https://github.com/KEINOS/google-ime-user-dictionary-ja-en)
(カタカナ語→英語つづり) も同梱し、「ぶらっく」で black が出る。

後者は EDICT 由来の翻訳なので、性質の違う 2 種類が混ざっている。
ぶらっく→black は要るが、くろ→black は要らないし、
そ→「5th note in the tonic solfa representation of the diatonic scale」は論外。
`scripts/filter_katakana_english.py` が音写だけを残す。判定は音韻で、日本語は子音を裸で
終われないため英語をカナで書くと必ず母音が挿入される。その母音を捨てると子音が揃う
(`burakku` → b r k k → `brk`、`black` → b l k → `brk`)。訳語にはこの関係が無い。
37,000 件中 10,000 件ほどが落ちる。

どちらも `scripts/fetch_dictionaries.sh` がビルド時に取得し、APK に入る。

mozc.data に焼き込むのではなく ユーザー辞書として取り込むステム辞書に入れるには
mozc の bazel 辞書ソースに手を入れることになり、辞書を差し替えるたびに 18 MB の
ネイティブデータを作り直す羽目になる。取り込みは初回起動時にバックグラウンドで一度だけ走り、
`IMPORT_USER_DICTIONARY` が TSV の解析・同名辞書の置き換え・即時リロード・保存まで面倒を見る。

### ユーザー辞書

設定 →「ユーザー辞書」から単語を 1 件ずつ追加・編集・削除できる。品詞は
mozc が TSV で受け付ける 45 種類から選ぶ。

この mozc には単語単位の API が無いSEND_USER_DICTIONARY_COMMAND` は reserved で、
入口は辞書まるごとを名前で置き換える `IMPORT_USER_DICTIONARY` だけ。そこで一覧の正本を
手元に持ち、編集のたびに全体を投げ直している。個人辞書のサイズなら書き直しのコストは
無視できるし、追加・編集・削除がすべて同じ操作になる (空を投げれば辞書ごと消える)。

### 誤字修正

OSS 版 mozc に誤字修正は入っていない。上流は予測器側の配線
(`SupplementalModelInterface::CorrectComposition` を呼び、補正後の読みを辞書引きして
`TYPING_CORRECTION` を付ける処理) を残したまま、モデル本体だけを社内に置いている。
OSS ビルドが積むのは常に `nullopt` を返す `SupplementalModelStub`。

そこで `patches/0001` で `TypingCorrectionModel` を追加し、スタブと差し替えている。
周辺の機構には触っていない。生成する仮説は 3 種類。

| 種類                    | 例          |
| --------------------- | ---------- |
| フリック方向ずれ (同一キー内の母音違い) | ありがとお ありがと |
| っ の脱落                 | がこ→ がう     |
| 隣接転置                  | にほんこ 日本語   |

距離は実際のキー配置るので、方向ずれは指が本当にどれだけ離れていたかで順位が付く。
その幾何は `scripts/gen_flick_layout.py` が配列と一緒に出力するため、両者がずれない。

濁点・半濁点・小書きの誤りはこの補正の対象外で、`kana_modifier_insensitive_conversion`
(Request/Config 両方で有効化済み) が吸収する。辞書引きの範囲を広げるだけなので追加コストがない。

候補数は 6 に絞ってある。び出し側は補正候補 1 件ごとに
unigram / realtime / bigram / number の全アグリゲーションを回すため、
デスクトップ実測で 1 件あたり約 5 ms かかる。12 件にすると予測が 6 倍遅くなった。
配分は生成順ではなく「もっともらしさ順」で、直前に打ったかなの方向ずれを最優先にする
(先着順にすると後段の生成器が枯れて、がう か がとの
ら出なくなる)。

`Config.use_typing_correction` と `use_kana_modifier_insensitive_conversion` は
どちらも既定 off なので、クライアント側で `SET_CONFIG` して有効化している。

### 候補の順序

候補は 4 段に分けて出す。段の中の順序はエンジンが出したまま触らない。

1. mozc の完全一致
2. 同梱辞書・ユーザー辞書の完全一致
3. mozc の非完全一致 (予測)
4. 辞書の非完全一致

mozc のモバイル予測は「打った通りの変換」と「まだ入力が続く前提の予測」を同じリストに
コストで混ぜるため、放っておくと でんわ で 電話番号 が 電話 より上に出る。
段の判定は `all_candidate_words` から取る。`key` は候補の読みが入力と違うときだけ設定され、
`USER_DICTIONARY` 属性が辞書由来を示す。

### 左文脈

カーソルの左に確定済みのテキストがあれば `Context.preceding_text` として mozc に渡す。
mozc はこれを history segment に変換し、候補順位に効かせる。

ただし上流が history を復元するのは数字と英字だけ日本語の左文脈は捨てられる。
スマホではそちらが常態なので、実質ほとんどの文節が文脈なしで変換されていた。
`patches/0004` で漢字・カタカナまで広げ、さらに左文脈が名詞的なときに助詞始まりの候補を
繰り上げる rewriter を足した。東京 のあとの にいきます が に活きます に、
彼 のあとの になった が 担った になっていた類が対象。

保守的にしてある。既に上位に居る候補を繰り上げるだけなので、接戦を決めるだけで答えを
捏造しない。末尾のひらがなは名詞扱いしない — 確定済みテキストの末尾のひらがなは
助詞や活用語尾のことが多いため。

パスワード欄には種別だけを渡し、本文は渡さない。


## カスタマイズ

配列とテーマは JSON。読み込み順は ユーザーディレクトリ → アセット、
同じ id のファイルをアプリの `files/layouts/` に置けば同梱版を上書きし、削除すれば既定に戻る。

```
files/layouts/flick_kana.json    配列 (キー配置, フリック割り当て, 動作)
files/themes/default_dark.json   配色, キー高さ, 角丸, ハプティクス, キー塗り
```

### 外観・キーボード設定 (端末ごと)

テーマが「持ち運べる見た目」なのに対し、こちらはこの端末固有の選択なので
テーマファイルではなく SharedPreferences に置く。

- ピュアブラック 背景を `#000000` にする。有機ELでは黒画素が消灯する。
  システムがライトテーマでも配色はダーク側を使う (黒地に明色のラベルが要るため)。
- キーボード背景画像 選んだ画像はアプリの files 配下に*する。
  E は SAF の権限を持たない別プロセスで動くうえ、元ファイルは後から削除されうるため。
  濃さは調整でき、既定は 45%。
- キーボードの高さ 絶対値ではなくテーマ値への倍率。どのテーマとも噛み合う。
- フリック中の表示 既定は「離したら入る文字」を 1 つだけ出す。従来の 4 方向表示も
  選べる。あちらは配列を覚えられる代わりに、見ようとしている隣接キーを 4 つとも覆う。

キーは既定で塗りを持たないベルがある以上キーごとの枠は情報を足さず、
コントラストを食って背景画像の邪魔になるだけなので。従来の塗り表示に戻すには
テーマの `flatKeys` を `false` にする。

### 候補が無いときの候補欄

変換するものが無いとき、候補欄にはクリップボードと「元に戻す」が出る。
Gboard がそこに置いているもので、キーボードから離れずには届かない 2 つ。

クリップボード履歴はメモリ上にだけ。ここが他と違うのは意図的で、
このアプリが覚える他のものは全て Keystore 鍵で暗号化してディスクに書くが、
クリップボードは性質が違う — パスワードマネージャがパスワードを置く場所であり、
どれもこのキーボードで打たれたものではない。機微フラグの立ったクリップは記録もしない。

## プライバシー

ユーザーが打った内容についてキーボードが保存するものは、すべて Android Keystore の鍵で
暗号化する。セキュアエレメントのある端末では鍵を取り出せない。

| ファイル                            | 書き手         |
| ------------------------------- | ----------- |
| `files/user_dictionary.enc`     | ユーザー辞書の編集画面 |
| `files/mozc/user_dictionary.db` | mozc        |
| `files/mozc/.history.db`        | mozc の変換学習  |

mozc は履歴を元から暗号化しているが、Android が継承してしまう Linux 版の
`PasswordManager` は鍵を保護対象と同じディレクトリに平文で (Android は Linux なので)。
`patches/0002` でこれを Java 側から注入する Keystore 由来の鍵に差し替え、
暗号化の無かったユーザー辞書にも同じ仕組みを広げた。

`android:allowBackup="false"` により、どれもクラウドバックアップに乗らない。

## 未実装・既知の制限

- Gboard がこの記号面の `!?#` に持つ 2 ページ目は未実装。その位置は現状「かな」に戻る。

## ライセンス

- 本体: Apache License 2.0
- `third_party/mozc`: BSD 3-Clause (Google Inc.)
- 同梱辞書 `mozc.data`: mozc OSS 辞書由来。構成要素ごとのライセンスは
  上流の `data/dictionary_oss/README.txt` を参照
- 同梱アイコン: [Bootstrap Icons](https://icons.getbootstrap.com), MIT

同梱辞書の条件を含む全文は [NOTICE](NOTICE) を参照。
