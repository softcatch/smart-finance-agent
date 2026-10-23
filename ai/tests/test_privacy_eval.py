from evals.privacy import altered, evaluate, leaked_values, load_cases, run_case


def test_원문이_LLM이_받은_글에_남았는지_종류별로_센다():
    seen = ["너는 비서다", "내 번호는 010-1234-5678이야", "[계좌1]로 보내줘"]
    pii = {"전화번호": ["010-1234-5678", "02-123-4567"], "계좌번호": ["123456789012"], "이메일": []}

    assert leaked_values(seen, pii) == {"전화번호": ["010-1234-5678"]}  # 남은 값만, 없으면 빠진다


def test_정상_문장이_달라졌으면_오탐이다():
    assert not altered(["1,000,000원 보내줘"], ["1,000,000원 보내줘"])
    assert altered(["123456789012로 보내줘"], ["[계좌1]로 보내줘"])


def test_한_대화를_그래프에_흘려_LLM이_받은_글을_모은다():
    seen, received = run_case(["안녕", "고마워"], "t_privacy")

    assert received == ["안녕", "고마워"]  # 턴마다 추출기가 받은 사용자 글
    assert "안녕" in seen and "고마워" in seen


def test_개인정보_문장_세트에서_LLM이_받은_원문은_0건이고_정상_문장은_바뀌지_않는다():
    result = evaluate(load_cases())

    assert result["pii_cases"] >= 20 and result["controls"] >= 10  # 세트가 비어 통과하는 일이 없게
    assert sum(result["leaked"].values()) == 0, result["examples"]
    assert result["changed"] == 0, result["examples"]
