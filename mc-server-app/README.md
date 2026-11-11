# 마크 서버 (안드로이드 앱)

폰에서 마인크래프트 **자바 에디션** 서버(Paper / 바닐라)를 돌리는 앱입니다.

## 설치
1. 이 저장소의 **Releases → `mc-server-app-latest`** 에서 `McServer.apk` 를 폰으로 받습니다.
2. "출처를 알 수 없는 앱 설치"를 허용하고 설치합니다. (arm64 폰 전용, Android 8 이상)

## 사용법
1. 서버 종류(Paper 추천), 버전, RAM, 포트를 정하고 EULA 동의 체크
2. **▶ 서버 시작** — 처음엔 Java 설치 + 서버 파일 다운로드로 몇 분 걸립니다
3. 같은 와이파이의 친구는 화면에 표시된 `IP:포트` 로 접속
4. **🌍 외부 접속 허용**을 켜두면 노란 박스에 `bore.pub:12345` 같은 주소가 뜹니다.
   **복사** 눌러서 친구에게 보내면 다른 와이파이/LTE에서도 접속 가능 (공유기 설정 불필요)
5. 콘솔에 `op 닉네임`, `whitelist add 닉네임` 같은 명령어 입력 가능

## 참고
- 화면을 꺼도 알림이 떠 있는 동안 서버가 계속 돕니다. 설정에서 이 앱의 **배터리 최적화를 끄면** 더 안정적입니다.
- 외부 접속은 무료 공개 터널 [bore.pub](https://github.com/ekzhang/bore) 을 사용합니다. 주소가 공개되므로
  `whitelist on` + `whitelist add 친구닉네임` 으로 화이트리스트를 켜두는 걸 추천합니다.
  앱은 지난번 포트를 다시 요청하지만 다른 사람이 쓰고 있으면 포트가 바뀔 수 있습니다.
- 월드/플러그인 파일 위치: `Android/data/com.play.mcserver/files/server`
- 휴대폰 성능상 4~8명 정도가 적당합니다.

## 빌드 구조
- `scripts/build_runtime.py` : Termux 저장소에서 Android용 OpenJDK 21을 받아 `assets/runtime.zip` 생성
- `.github/workflows/mc-server-app.yml` : 푸시할 때마다 GitHub Actions가 APK를 빌드해 릴리스에 올림
- 앱이 내부 저장소의 `java` 를 직접 실행해야 해서 `targetSdk` 는 28 입니다 (Termux와 같은 방식).
