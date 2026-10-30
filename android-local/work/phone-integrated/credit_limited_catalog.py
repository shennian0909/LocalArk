"""Add catalog-defined limited and crossover operators to the local credit shop."""
import copy
import json


def extend_credit_catalog(data_root, original):
    table = json.loads((data_root / 'excel/gacha_table.json').read_text(encoding='utf-8'))
    characters = json.loads((data_root / 'excel/character_table.json').read_text(encoding='utf-8'))
    limited = set()
    for pool in table['gachaPoolClient']:
        param = pool.get('limitParam')
        if param and param.get('limitedCharId'):
            limited.add(param['limitedCharId'])
        if pool['gachaRuleType'] == 'LINKAGE':
            details = json.loads((data_root / 'gacha' / (pool['gachaPoolId'] + '.json')).read_text(encoding='utf-8'))
            for bucket in details['detailInfo']['upCharInfo']['perCharList']:
                limited.update(bucket['charIdList'])
    catalog = copy.deepcopy(original)
    existing = {good['item']['id'] for good in catalog['goodList']}
    additions = []
    for char_id in sorted(limited, key=lambda cid: characters[cid]['sortIndex']):
        if char_id in existing:
            continue
        character = characters[char_id]
        assert not character['isNotObtainable'], char_id
        additions.append({'goodId': 'good_LOCAL_LIMITED_' + char_id,
                          'displayName': character['name'], 'originPrice': 160,
                          'price': 160, 'discount': 0, 'sortId': -1000 + len(additions),
                          'availCount': 6, 'item': {'id': char_id, 'type': 'CHAR', 'count': 1}})
    catalog['goodList'] = additions + catalog['goodList']
    return catalog, additions
