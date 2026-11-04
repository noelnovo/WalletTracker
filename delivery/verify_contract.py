"""Secret-free source and optional live API compatibility verification."""
import argparse
import json
import pathlib
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--live', action='store_true')
    parser.add_argument('--expected-revision')
    args = parser.parse_args()
    contract = json.loads((ROOT / 'delivery/integration-contract.json').read_text())
    assert contract['production_api'] == 'https://api.downops.win'
    assert contract['api_version'] == '1'
    client = (ROOT / 'app/src/main/java/win/downops/wallettracker/data/api/ApiClient.kt').read_text()
    assert 'ApiUrls.baseUrl(BuildConfig.API_BASE_URL)' in client
    assert 'ApiUrls.readinessUrl(BASE_URL, BuildConfig.API_VERSION)' in client
    assert '.use { it.isSuccessful }' in client
    urls = (ROOT / 'app/src/main/java/win/downops/wallettracker/data/api/ApiUrls.kt').read_text()
    assert 'api/v$version/ready' in urls
    assert contract['preserve_existing_account_and_tenant_crypto']
    if args.live:
        request = urllib.request.Request(
            contract['production_api'] + contract['readiness_path'],
            headers={'User-Agent': 'Mozilla/5.0 WalletTracker-Android-Compatibility'},
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            assert response.status == 200 and json.load(response)['success']
            if args.expected_revision:
                assert response.headers.get('X-WalletTracker-Revision') == args.expected_revision
    print(json.dumps({'android_api_contract_verified': True, 'live_readiness_verified': args.live,
                      'apk_built_or_published': False}))


if __name__ == '__main__':
    main()
