# 마크 서버 (안드로이드 앱)

폰에서 마인크래프트 **자바 에디션** 서버(Paper / 바닐라)를 돌리는 앱입니다.

## 설치
1. 이 저장소의 **Releases → `mc-server-app-latest`** 에서 `McServer.apk` 를 폰으로 받습니다.
2. "출처를 알 수 없는 앱 설치"를 허용하고 설치합니다. (arm64 폰 전용, Android 8 이상)

## 사용법
1. 서버 종류(Paper 추천), 버전, RAM, 포트를 정하고 EULA 동의 체크
2. **▶ 서버 시작** — 처음엔 Java 설치 + 서버 파일 다운로드로 몇 분 걸립니다
3. 같은 와이파이의 친구는 화면에 표시된 `IP:포트` 로 접속
4. 콘솔에 `op 닉네임`, `whitelist add 닉네임` 같은 명령어 입력 가능

## 참고
- 화면을 꺼도 알림이 떠 있는 동안 서버가 계속 돕니다. 설정에서 이 앱의 **배터리 최적화를 끄면** 더 안정적입니다.
- 밖에 있는 친구가 접속하려면 공유기 포트포워딩이나 playit.gg 같은 터널 서비스가 필요합니다.
- 월드/플러그인 파일 위치: `Android/data/com.play.mcserver/files/server`
- 휴대폰 성능상 4~8명 정도가 적당합니다.

## 빌드 구조
- `scripts/build_runtime.py` : Termux 저장소에서 Android용 OpenJDK 21을 받아 `assets/runtime.zip` 생성
- `.github/workflows/mc-server-app.yml` : 푸시할 때마다 GitHub Actions가 APK를 빌드해 릴리스에 올림
- 앱이 내부 저장소의 `java` 를 직접 실행해야 해서 `targetSdk` 는 28 입니다 (Termux와 같은 방식).
