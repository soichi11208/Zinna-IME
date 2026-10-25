# zenzai-plus（実験的）

設定のトグルをONにすると、同梱した `zenzai-plus-ft-f16.gguf` で日本語の
変換候補を生成し、先頭に表示する。候補をタップするか、生成後に確定キーを押すと確定する。
OFFにするとMozcだけで変換し、読み込んだモデルを解放する。初期値はOFF。
ON中は「Mozc候補を併用」のトグルでMozcの有無を選べる（初期値OFF）。
併用OFFではMozcのセッションを作らず、キー入力・かな組み立て・変換を独立経路で処理する。
併用ONではMozcの候補も加える。辞書候補・Mozc候補のどちらが存在していても、
zenzai-plusの生成結果を必ず先頭に表示し、確定キーもその結果を選ぶ。
トグル変更はキーボードを再表示せずに反映する。

推論は端末内のCPUで行う。モデルの展開・読み込み・推論は専用ワーカーで行い、
入力が進んだら古い推論を中止する。パスワード欄、英数・数字面ではモデルを使わない。
最初の読み込み中や失敗時にも、かな入力とユーザー辞書の候補は利用できる。
失敗時はトーストで知らせる。入力本文・文脈を学習用の平文ファイルへ保存しない。

## TTTと辞書学習

参照元の `zenzaiTTTAdapter.swift` と `NativeZenzaiTTTTrainer` に沿い、推論とは別の
モデル・ワーカーで `llama_ttt_apply`（学習率1e-4）を実行する。確定入力の読み・直前文脈・
選んだ表記を使い、2秒の待機後に直近の同じ読みの修正をまとめて学習する。
パスワード欄、学習禁止フラグのある入力欄、クリップボードから貼った本文は学習しない。

既存のユーザー辞書に登録・編集した単語は `llama_ttt_learn_word` で学習する。
設定の「ユーザー辞書を学習」からも要求できる。最大32ステップ、参照元の500ms制限を使用。
収束しない場合も登録語は辞書候補として使える。設定にはTTT更新件数と学習済み辞書語数を表示する。

更新後は `llama_ttt_export_version` / `llama_ttt_import_version` で不変の重みを
推論側へ渡す。学習中に推論側の重みを変更しない。モデル更新はCPUでも動作するように、
参照元にあったmmapバッファのデバイス検出を修正している（`patches/zenzai-0002-*`）。

チェックポイントはAndroid KeystoreのAES-GCMで暗号化して保存する。
Javaヒープを圧迫しないよう1MBごとに認証し、順序・終端も認証する。
ネイティブ側の平文スナップショットはmemfdのRAM内だけに置き、復号・認証完了後に読み込む。
元GGUFと学習状態を分け、再起動・OFF/ON後は同じモデル用の学習状態を復元する。
辞書の削除は候補へ反映するが、それまでのTTT更新の逆学習は行わない。

## 任意ビルド

通常ビルドにはモデルもネイティブライブラリも含まれない。
指定モデルを含めるビルドは現在arm64-v8a用。

```bash
python3 scripts/prepare_zenzai.py /path/to/zenzai+
bash scripts/build_zenzai.sh
./gradlew :app:testDebugUnitTest :app:assembleRelease -Pzenzai=true
```

準備ディレクトリには、ユーザーが指定したGGUF、`MODEL_CARD.draft.md`、
`zenzai-plus-Hazkey-Fcitx5/hazkey-server/llama.cpp/` が必要。
準備スクリプトは互換ランタイムのソースとモデルをgit管理外へコピーする。
モデルは192,132,704バイト、SHA-256:
`928fc0f32dbafb4cd2b26275d1b40cefeb47aa27cd7b7144f92924363a7ec8f4`。
モデル更新後は同じ準備・ビルドを実行する。

NDKは `ANDROID_NDK_HOME`、SDKは `ANDROID_SDK_ROOT` または `ANDROID_HOME` で指定可能。
既定はこのリポジトリの `tools/android-sdk/ndk/27.2.12479018`。

## 確認した形式

指定ディレクトリの `tools/finetune_zenzai.py:encode_example`、
`data/ft-tweets/base-model/tokenizer_config.json`、ZenzContextのv2プロンプト処理を参照した。
プロンプトは `U+EE00 + カタカナ読み + [U+EE02 + 直前の40文字] + U+EE01`。
FT時と同じくBOSを追加せず、EOSまで貪欲生成する。チャットテンプレートは使用しない。
Swiftの辞書ラティスはリンクせず、Androidの入力処理から同じGGUFとネイティブTTT APIに接続している。

## ローカル検証

```bash
cmake -S native/zenzai -B build/zenzai-host -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/zenzai-host --parallel 8
build/zenzai-host/zenzai-check /path/to/zenzai-plus-ft-f16.gguf --self-test
build/zenzai-host/zenzai-check /path/to/zenzai-plus-ft-f16.gguf --ttt-test
```

生成、古い要求の拒否、推論途中の中止、中止後の再利用、コンテキスト長制限を確認する。
Android向けバイナリは16KBページ境界でリンクしている。
TTT試験では実重みの変化、学習中の推論重みの不変性、単語登録による「夢想夏郷」の生成、
重み公開、スナップショット復元、壊れた状態の拒否を同じネイティブ実装で確認する。

モデルはCC BY-SA 4.0、llama.cpp/GGMLはMIT。由来・モデルカードをAPKへ同梱する。
通常ビルドへの依存追加を避けるため、モデル同梱は明示オプションになっている。
