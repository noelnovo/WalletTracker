"""Exercise the real protected-tag production signer without retaining/releasing an APK."""
from build_prod import main, report_failure


if __name__ == '__main__':
    try:
        main(validation=True)
    except Exception as error:
        report_failure(error)
        raise
