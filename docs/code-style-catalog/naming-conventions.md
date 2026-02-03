# 命名規則 (Naming Conventions)

## CardDemo COBOL/CICS アプリケーション コードスタイルカタログ

本文書は、CardDemo メインフレームアプリケーションにおける命名規則パターンを定義します。すべてのコード生成は、本カタログのパターンに従う必要があります。

---

## 目次

- [必須パターン (MUST)](#必須パターン-must)
  - [プログラムID接頭辞](#プログラムid接頭辞)
  - [コピーブック命名](#コピーブック命名)
- [推奨パターン (SHOULD)](#推奨パターン-should)
  - [フィールド接頭辞](#フィールド接頭辞)
  - [FLG-条件接頭辞](#flg-条件接頭辞)
  - [マップ接尾辞パターン](#マップ接尾辞パターン)
- [命名規則サマリーテーブル](#命名規則サマリーテーブル)
- [関連ドキュメント](#関連ドキュメント)

---

## 必須パターン (MUST)

以下のパターンは**必須**です。違反は自動却下となります。

---

### プログラムID接頭辞

```yaml
パターン名: プログラムID接頭辞
優先度: MUST
カテゴリ: 命名規則
ファイルパス: app/cbl/*.cbl
スニペット: |
   IDENTIFICATION DIVISION.
   PROGRAM-ID.
       CBACT01C.    *> CB* = バッチプログラム
   
   IDENTIFICATION DIVISION.
   PROGRAM-ID.
       COACTUPC.    *> CO* = CICS オンラインプログラム
   
   IDENTIFICATION DIVISION.
   PROGRAM-ID.
       CSUTLDTC.    *> CS* = 共有ユーティリティ
検証基準:
  - バッチプログラムは CB* 接頭辞を使用すること
  - CICS オンラインプログラムは CO* 接頭辞を使用すること
  - 共有ユーティリティプログラムは CS* 接頭辞を使用すること
  - PROGRAM-ID はファイル名（拡張子除く）と一致すること
  - 接頭辞の後は英数字で4-6文字以内とすること
根拠: プログラム種別の即座識別を可能にするため。バッチとオンラインの区別、共有リソースの識別が容易になる。
```

**プログラムID接頭辞一覧:**

| 接頭辞 | 種別 | 説明 | 例 |
|--------|------|------|-----|
| `CB*` | バッチ | Batch (COBOL Batch) | `CBACT01C`, `CBTRN02C`, `CBSTM03A` |
| `CO*` | オンライン | CICS Online | `COACTUPC`, `COACTVWC`, `COSGN00C` |
| `CS*` | ユーティリティ | Shared Utility | `CSUTLDTC` |

**バッチプログラム例:**

```cobol
       IDENTIFICATION DIVISION.                                                 
       PROGRAM-ID.    CBACT01C.                                                 
       AUTHOR.        AWS.
```

**Source:** `app/cbl/CBACT01C.cbl:22-24`

**CICS オンラインプログラム例:**

```cobol
       IDENTIFICATION DIVISION.
       PROGRAM-ID.
           COACTUPC.
       DATE-WRITTEN.
           July 2022.
       DATE-COMPILED.
           Today.
```

**Source:** `app/cbl/COACTUPC.cbl:21-27`

---

### コピーブック命名

```yaml
パターン名: コピーブック命名
優先度: MUST
カテゴリ: 命名規則
ファイルパス: app/cpy/*.cpy, app/cpy-bms/*.CPY
スニペット: |
   COPY CVACT01Y.    *> CV* = VSAM レコードレイアウト
   COPY CSUTLDPY.    *> CS* = 共有定義（P = Procedure）
   COPY COCOM01Y.    *> CO* = プログラム固有
   COPY CSMSG02Y.    *> CS* + Y接尾辞 = データ定義
検証基準:
  - VSAM レコードレイアウトは CV* 接頭辞を使用すること
  - 共有定義・ユーティリティは CS* 接頭辞を使用すること
  - プログラム固有のコピーブックは CO* 接頭辞を使用すること
  - データ定義コピーブックは Y 接尾辞を使用すること
  - 手続き部コピーブックは P 接尾辞を使用すること（任意）
  - BMS コピーブックは .CPY 拡張子（大文字）を使用すること
根拠: コピーブック目的の明確化とカテゴリ分類。種別が一目で識別でき、保守性が向上する。
```

**コピーブック命名規則一覧:**

| 接頭辞 | 用途 | 説明 | 例 |
|--------|------|------|-----|
| `CV*` | レコードレイアウト | VSAM Record Layout | `CVACT01Y`, `CVCUS01Y`, `CVTRA05Y` |
| `CS*` | 共有定義 | Shared Definition | `CSUTLDPY`, `CSMSG02Y`, `CSSETATY` |
| `CO*` | プログラム固有 | Program-Specific | `COCOM01Y`, `COADM02Y`, `COMEN02Y` |

**接尾辞規則:**

| 接尾辞 | 用途 | 説明 | 例 |
|--------|------|------|-----|
| `*Y` | データ定義 | Data Definition | `CVACT01Y`, `COCOM01Y` |
| `*P` | 手続き部 | Procedure Division | `CSUTLDPY` |
| `*W` | 作業領域 | Working Storage | `CSUTLDWY` |

**VSAM レコードレイアウト例:**

```cobol
      *****************************************************************
      *    Data-structure for  account entity (RECLN 300)
      *****************************************************************
       01  ACCOUNT-RECORD.
           05  ACCT-ID                           PIC 9(11).
           05  ACCT-ACTIVE-STATUS                PIC X(01).
           05  ACCT-CURR-BAL                     PIC S9(10)V99.
           05  ACCT-CREDIT-LIMIT                 PIC S9(10)V99.
           05  ACCT-CASH-CREDIT-LIMIT            PIC S9(10)V99.
           05  ACCT-OPEN-DATE                    PIC X(10).
           05  ACCT-EXPIRAION-DATE               PIC X(10). 
           05  ACCT-REISSUE-DATE                 PIC X(10).
           05  ACCT-CURR-CYC-CREDIT              PIC S9(10)V99.
           05  ACCT-CURR-CYC-DEBIT               PIC S9(10)V99.
           05  ACCT-ADDR-ZIP                     PIC X(10).
           05  ACCT-GROUP-ID                     PIC X(10).
           05  FILLER                            PIC X(178).
```

**Source:** `app/cpy/CVACT01Y.cpy:1-17`

**共有定義（COMMAREA）例:**

```cobol
       01 CARDDEMO-COMMAREA.
          05 CDEMO-GENERAL-INFO.
             10 CDEMO-FROM-TRANID             PIC X(04).
             10 CDEMO-FROM-PROGRAM            PIC X(08).
             10 CDEMO-TO-TRANID               PIC X(04).
             10 CDEMO-TO-PROGRAM              PIC X(08).
             10 CDEMO-USER-ID                 PIC X(08).
             10 CDEMO-USER-TYPE               PIC X(01).
                88 CDEMO-USRTYP-ADMIN         VALUE 'A'.
                88 CDEMO-USRTYP-USER          VALUE 'U'.
```

**Source:** `app/cpy/COCOM01Y.cpy:19-28`

---

## 推奨パターン (SHOULD)

以下のパターンは**推奨**です。デフォルトで適用されますが、逸脱には文書化された正当化理由が必要です。

---

### フィールド接頭辞

```yaml
パターン名: フィールド接頭辞
優先度: SHOULD
カテゴリ: 命名規則
ファイルパス: app/cbl/COACTUPC.cbl:35-47, app/cbl/CBACT01C.cbl:37-40
スニペット: |
   01  WS-MISC-STORAGE.
      05 WS-CICS-PROCESSNG-VARS.
         07 WS-RESP-CD                          PIC S9(09) COMP
                                                VALUE ZEROS.
         07 WS-REAS-CD                          PIC S9(09) COMP
                                                VALUE ZEROS.
         07 WS-TRANID                           PIC X(4)
                                                VALUE SPACES.
         07 WS-UCTRANS                          PIC X(4)
                                                VALUE SPACES.
   
   FD  ACCTFILE-FILE.
   01  FD-ACCTFILE-REC.
       05 FD-ACCT-ID                        PIC 9(11).
       05 FD-ACCT-DATA                      PIC X(289).
検証基準:
  - Working-Storage Section のフィールドは WS- 接頭辞を使用すること
  - File Description のフィールドは FD- 接頭辞を使用すること
  - Linkage Section のフィールドは LK- 接頭辞を使用すること
  - COMMAREA フィールドは CDEMO-/CC- 接頭辞を使用すること
  - 編集用変数は WS-EDIT- 接頭辞を使用すること
根拠: フィールドのスコープと用途の即座識別。コードレビューとデバッグが容易になる。
```

**フィールド接頭辞一覧:**

| 接頭辞 | セクション | 説明 | 例 |
|--------|-----------|------|-----|
| `WS-` | Working-Storage | 作業領域変数 | `WS-RESP-CD`, `WS-TRANID` |
| `FD-` | File Section | ファイル記述フィールド | `FD-ACCT-ID`, `FD-ACCT-DATA` |
| `LK-` | Linkage Section | リンケージセクション | `LK-COMMAREA` |
| `CDEMO-` | COMMAREA | 通信領域（CardDemo固有） | `CDEMO-USER-ID`, `CDEMO-ACCT-ID` |
| `CC-` | COMMAREA | 通信領域（汎用） | `CC-ACCOUNT-ID` |
| `WS-EDIT-` | Working-Storage | 編集・検証用変数 | `WS-EDIT-DATE-CCYY`, `WS-EDIT-US-PHONE-NUM` |
| `ACCT-` | Record Field | 口座レコードフィールド | `ACCT-ID`, `ACCT-CURR-BAL` |
| `TRAN-` | Record Field | トランザクションレコード | `TRAN-ID`, `TRAN-AMT` |
| `CUST-` | Record Field | 顧客レコードフィールド | `CUST-ID`, `CUST-FNAME` |

**Working-Storage 例:**

```cobol
       01  WS-MISC-STORAGE.
      ******************************************************************
      * General CICS related
      ******************************************************************
         05 WS-CICS-PROCESSNG-VARS.
            07 WS-RESP-CD                          PIC S9(09) COMP
                                                   VALUE ZEROS.
            07 WS-REAS-CD                          PIC S9(09) COMP
                                                   VALUE ZEROS.
```

**Source:** `app/cbl/COACTUPC.cbl:35-43`

**File Description 例:**

```cobol
       FD  ACCTFILE-FILE.                                                       
       01  FD-ACCTFILE-REC.                                                     
           05 FD-ACCT-ID                        PIC 9(11).                      
           05 FD-ACCT-DATA                      PIC X(289).
```

**Source:** `app/cbl/CBACT01C.cbl:37-40`

---

### FLG-条件接頭辞

```yaml
パターン名: FLG-条件接頭辞
優先度: SHOULD
カテゴリ: 命名規則
ファイルパス: app/cbl/COACTUPC.cbl:56-80
スニペット: |
           10 WS-FLG-SIGNED-NUMBER-EDIT            PIC X(1).
              88  FLG-SIGNED-NUMBER-ISVALID        VALUE LOW-VALUES.
              88  FLG-SIGNED-NUMBER-NOT-OK         VALUE '0'.
              88  FLG-SIGNED-NUMBER-BLANK          VALUE 'B'.
   
           10 WS-EDIT-ALPHA-ONLY-FLAGS             PIC X(1).
              88  FLG-ALPHA-ISVALID                VALUE LOW-VALUES.
              88  FLG-ALPHA-NOT-OK                 VALUE '0'.
              88  FLG-ALPHA-BLANK                  VALUE 'B'.
   
           10 WS-EDIT-ALPHANUM-ONLY-FLAGS          PIC X(1).
              88  FLG-ALPHNANUM-ISVALID            VALUE LOW-VALUES.
              88  FLG-ALPHNANUM-NOT-OK             VALUE '0'.
              88  FLG-ALPHNANUM-BLANK              VALUE 'B'.
検証基準:
  - すべてのフラグ条件は FLG- 接頭辞を使用すること
  - 有効状態を示す条件は -ISVALID 接尾辞を使用すること
  - 無効状態を示す条件は -NOT-OK 接尾辞を使用すること
  - 空白状態を示す条件は -BLANK 接尾辞を使用すること
  - ISVALID 条件の VALUE は LOW-VALUES とすること（未設定＝有効の初期状態）
  - NOT-OK 条件の VALUE は '0' とすること
  - BLANK 条件の VALUE は 'B' または SPACES とすること
根拠: 条件名の一貫性と可読性向上。SET 文での状態管理が明確になる。
```

**88レベル条件命名パターン:**

| パターン | 用途 | VALUE | 例 |
|----------|------|-------|-----|
| `FLG-*-ISVALID` | 有効状態 | `LOW-VALUES` | `FLG-YEAR-ISVALID` |
| `FLG-*-NOT-OK` | 無効状態 | `'0'` | `FLG-YEAR-NOT-OK` |
| `FLG-*-BLANK` | 空白状態 | `'B'` or `SPACES` | `FLG-YEAR-BLANK` |

**日付検証フラグ例:**

```cobol
       10 WS-EDIT-DATE-CCYY              PIC X(04).
          88 WS-EDIT-DATE-CCYY-ISBLANK   VALUE SPACES.
       10 WS-EDIT-DATE-CCYY-N REDEFINES
          WS-EDIT-DATE-CCYY              PIC 9(04).
          88 THIS-CENTURY                VALUE 2000 THRU 2099.
          88 LAST-CENTURY                VALUE 1900 THRU 1999.
       10 WS-EDIT-DATE-CC-N              PIC 9(02).
       10 WS-FLG-YEAR-EDIT               PIC X(01).
          88 FLG-YEAR-ISVALID            VALUE LOW-VALUES.
          88 FLG-YEAR-NOT-OK             VALUE '0'.
          88 FLG-YEAR-BLANK              VALUE 'B'.
```

**Source:** `app/cpy/CSUTLDWY.cpy` (CSUTLDPY参照)

**入力検証フラグ例:**

```cobol
         05  WS-INPUT-FLAG                         PIC X(1).
           88  INPUT-OK                            VALUE '0'.
           88  INPUT-ERROR                         VALUE '1'.
           88  INPUT-PENDING                       VALUE LOW-VALUES.
         05  WS-RETURN-FLAG                        PIC X(1).
           88  WS-RETURN-FLAG-OFF                  VALUE LOW-VALUES.
           88  WS-RETURN-FLAG-ON                   VALUE '1'.
         05  WS-PFK-FLAG                           PIC X(1).
           88  PFK-VALID                           VALUE '0'.
           88  PFK-INVALID                         VALUE '1'.
```

**Source:** `app/cbl/COACTUPC.cbl:171-180`

**APPL-RESULT コード例:**

```cobol
       01  APPL-RESULT             PIC S9(9)   COMP.                            
           88  APPL-AOK            VALUE 0.                                     
           88  APPL-EOF            VALUE 16.
```

**Source:** `app/cbl/CBACT01C.cbl:61-63`

---

### マップ接尾辞パターン

```yaml
パターン名: マップ接尾辞パターン
優先度: SHOULD
カテゴリ: 命名規則
ファイルパス: app/cpy-bms/*.CPY
スニペット: |
   01  CACTUPAI.                    *> AI = Input View (入力ビュー)
       02  FILLER PIC X(12).
       02  TRNNAMEL    COMP  PIC  S9(4).
       02  TRNNAMEF    PICTURE X.
       ...
       02  TRNNAMEI  PIC X(4).      *> I接尾辞 = Input Field
   
   01  CACTUPAO REDEFINES CACTUPAI. *> AO = Output View (出力ビュー)
       02  FILLER PIC X(12).
       02  FILLER PICTURE X(3).
       02  TRNNAMEC    PICTURE X.   *> C接尾辞 = Color Attribute
       02  TRNNAMEP    PICTURE X.   *> P接尾辞 = Protection Attribute
       02  TRNNAMEH    PICTURE X.   *> H接尾辞 = Highlight Attribute
       02  TRNNAMEV    PICTURE X.   *> V接尾辞 = Validation Attribute
       02  TRNNAMEO  PIC X(4).      *> O接尾辞 = Output Field
検証基準:
  - BMS入力ビューは AI 接尾辞を使用すること（例: CACTUPAI）
  - BMS出力ビューは AO 接尾辞を使用すること（例: CACTUPAO）
  - 出力ビューは入力ビューを REDEFINES すること
  - フィールド入力値は I 接尾辞を使用すること（例: TRNNAMEI）
  - フィールド出力値は O 接尾辞を使用すること（例: TRNNAMEO）
  - 色属性フィールドは C 接尾辞を使用すること
  - 保護属性フィールドは P 接尾辞を使用すること
  - ハイライト属性フィールドは H 接尾辞を使用すること
根拠: BMS入出力ビューの明確な区別。SEND/RECEIVE MAP 操作での適切なビュー選択が保証される。
```

**BMSマップ接尾辞一覧:**

| 接尾辞 | 用途 | 説明 | 例 |
|--------|------|------|-----|
| `*AI` | 入力ビュー | Input Map Structure | `CACTUPAI`, `COSGN0AI` |
| `*AO` | 出力ビュー | Output Map Structure | `CACTUPAO`, `COSGN0AO` |
| `*I` | 入力フィールド | Input Field Value | `TRNNAMEI`, `ACCTIDI` |
| `*O` | 出力フィールド | Output Field Value | `TRNNAMEO`, `ACCTIDO` |
| `*L` | 長さ | Length Field | `TRNNAMEL` |
| `*F` | フラグ | Flag/Attribute Field | `TRNNAMEF` |
| `*A` | 属性 | Attribute Field | `TRNNAMEA` |
| `*C` | 色 | Color Attribute | `TRNNAMEC` |
| `*P` | 保護 | Protection Attribute | `TRNNAMEP` |
| `*H` | ハイライト | Highlight Attribute | `TRNNAMEH` |
| `*V` | 検証 | Validation Attribute | `TRNNAMEV` |

**入力ビュー (AI) 例:**

```cobol
       01  CACTUPAI.
           02  FILLER PIC X(12).
           02  TRNNAMEL    COMP  PIC  S9(4).
           02  TRNNAMEF    PICTURE X.
           02  FILLER REDEFINES TRNNAMEF.
             03 TRNNAMEA    PICTURE X.
           02  FILLER   PICTURE X(4).
           02  TRNNAMEI  PIC X(4).
```

**Source:** `app/cpy-bms/COACTUP.CPY:17-24`

**出力ビュー (AO) 例:**

```cobol
       01  CACTUPAO REDEFINES CACTUPAI.
           02  FILLER PIC X(12).
           02  FILLER PICTURE X(3).
           02  TRNNAMEC    PICTURE X.
           02  TRNNAMEP    PICTURE X.
           02  TRNNAMEH    PICTURE X.
           02  TRNNAMEV    PICTURE X.
           02  TRNNAMEO  PIC X(4).
```

**Source:** `app/cpy-bms/COACTUP.CPY:343-350`

---

## 命名規則サマリーテーブル

### プログラム接頭辞

| 種別 | 接頭辞 | 例 | 必須/推奨 |
|------|--------|-----|----------|
| バッチプログラム | `CB*` | CBACT01C, CBTRN02C | MUST |
| CICSオンライン | `CO*` | COACTUPC, COSGN00C | MUST |
| 共有ユーティリティ | `CS*` | CSUTLDTC | MUST |

### コピーブック接頭辞

| 種別 | 接頭辞 | 例 | 必須/推奨 |
|------|--------|-----|----------|
| VSAMレコード | `CV*` | CVACT01Y, CVCUS01Y | MUST |
| 共有定義 | `CS*` | CSUTLDPY, CSMSG02Y | MUST |
| プログラム固有 | `CO*` | COCOM01Y, COADM02Y | MUST |

### フィールド接頭辞

| セクション | 接頭辞 | 例 | 必須/推奨 |
|-----------|--------|-----|----------|
| Working-Storage | `WS-` | WS-RESP-CD | SHOULD |
| File Description | `FD-` | FD-ACCT-ID | SHOULD |
| Linkage Section | `LK-` | LK-COMMAREA | SHOULD |
| COMMAREA | `CDEMO-`/`CC-` | CDEMO-USER-ID | SHOULD |
| 編集変数 | `WS-EDIT-` | WS-EDIT-DATE-CCYY | SHOULD |

### 88レベル条件接尾辞

| 状態 | 接尾辞 | VALUE | 必須/推奨 |
|------|--------|-------|----------|
| 有効 | `-ISVALID` | LOW-VALUES | SHOULD |
| 無効 | `-NOT-OK` | '0' | SHOULD |
| 空白 | `-BLANK` | 'B' or SPACES | SHOULD |

### BMSマップ接尾辞

| ビュー/フィールド | 接尾辞 | 例 | 必須/推奨 |
|-----------------|--------|-----|----------|
| 入力ビュー | `*AI` | CACTUPAI | SHOULD |
| 出力ビュー | `*AO` | CACTUPAO | SHOULD |
| 入力フィールド | `*I` | TRNNAMEI | SHOULD |
| 出力フィールド | `*O` | TRNNAMEO | SHOULD |

---

## 関連ドキュメント

本文書は以下のドキュメントと関連しています：

- [アーキテクチャパターン](./architecture-patterns.md) - プログラム構造とDIVISION組織
- [データ契約](./data-contracts.md) - コピーブック構造とレコードレイアウト
- [BMSパターン](./bms-patterns.md) - BMS画面定義パターン
- [検証パターン](./validation-patterns.md) - 88レベル条件を使用した入力検証

---

## 準拠レポートテンプレート

命名規則に関する準拠レポートは以下の形式を使用してください：

```plaintext
=== カタログ準拠レポート ===
生成ファイル: [パス]
適用ルール数: [N]
MUST準拠: [合格/不合格]
SHOULD準拠: [合格/逸脱あり]
逸脱項目:
  - [ルール名]: [正当化理由]
```

**命名規則チェックリスト:**

- [ ] プログラムIDが正しい接頭辞（CB*/CO*/CS*）を使用している
- [ ] コピーブック名が正しい接頭辞（CV*/CS*/CO*）を使用している
- [ ] Working-Storageフィールドが WS- 接頭辞を使用している
- [ ] ファイル記述フィールドが FD- 接頭辞を使用している
- [ ] 88レベル条件が FLG- 接頭辞と適切な接尾辞を使用している
- [ ] BMSマップが AI/AO 接尾辞パターンを使用している

---

<!-- Ver: CodeStyleCatalog_v1.0 Date: 2026-02-03 -->
