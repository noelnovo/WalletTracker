"""Exercise the real protected-tag production signer without retaining/releasing an APK."""
from build_prod import main


if __name__ == '__main__':
    main(validation=True)
