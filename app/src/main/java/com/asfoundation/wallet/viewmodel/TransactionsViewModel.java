package com.asfoundation.wallet.viewmodel;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.text.format.DateUtils;
import android.util.Pair;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.appcoins.wallet.gamification.repository.Levels;
import com.asfoundation.wallet.C;
import com.asfoundation.wallet.entity.Balance;
import com.asfoundation.wallet.entity.ErrorEnvelope;
import com.asfoundation.wallet.entity.GlobalBalance;
import com.asfoundation.wallet.entity.NetworkInfo;
import com.asfoundation.wallet.entity.Wallet;
import com.asfoundation.wallet.interact.TransactionViewInteract;
import com.asfoundation.wallet.navigator.TransactionViewNavigator;
import com.asfoundation.wallet.referrals.CardNotification;
import com.asfoundation.wallet.referrals.InviteFriendsActivity;
import com.asfoundation.wallet.support.SupportInteractor;
import com.asfoundation.wallet.transactions.Transaction;
import com.asfoundation.wallet.transactions.TransactionsAnalytics;
import com.asfoundation.wallet.ui.AppcoinsApps;
import com.asfoundation.wallet.ui.appcoins.applications.AppcoinsApplication;
import com.asfoundation.wallet.ui.iab.FiatValue;
import com.asfoundation.wallet.ui.widget.entity.TransactionsModel;
import com.asfoundation.wallet.ui.widget.holder.ApplicationClickAction;
import com.asfoundation.wallet.ui.widget.holder.CardNotificationAction;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class TransactionsViewModel extends BaseViewModel {
  private static final long GET_BALANCE_INTERVAL = 30 * DateUtils.SECOND_IN_MILLIS;
  private static final long FETCH_TRANSACTIONS_INTERVAL = 30 * DateUtils.SECOND_IN_MILLIS;
  private static final int FIAT_SCALE = 2;
  private static final BigDecimal MINUS_ONE = new BigDecimal("-1");
  private final MutableLiveData<NetworkInfo> defaultNetwork = new MutableLiveData<>();
  private final MutableLiveData<Wallet> defaultWallet = new MutableLiveData<>();
  private final MutableLiveData<TransactionsModel> transactionsModel = new MutableLiveData<>();
  private final MutableLiveData<CardNotification> dismissNotification = new MutableLiveData<>();
  private final MutableLiveData<Boolean> showNotification = new MutableLiveData<>();
  private final MutableLiveData<GlobalBalance> defaultWalletBalance = new MutableLiveData<>();
  private final MutableLiveData<Double> gamificationMaxBonus = new MutableLiveData<>();
  private final MutableLiveData<Double> fetchTransactionsError = new MutableLiveData<>();
  private final MutableLiveData<Boolean> unreadMessages = new MutableLiveData<>();
  private final MutableLiveData<String> shareApp = new MutableLiveData<>();
  private final AppcoinsApps applications;
  private final TransactionsAnalytics analytics;
  private final TransactionViewNavigator transactionViewNavigator;
  private final TransactionViewInteract transactionViewInteract;
  private final SupportInteractor supportInteractor;
  private final Handler handler = new Handler();
  private CompositeDisposable disposables;
  private final Runnable startGlobalBalanceTask = this::getGlobalBalance;
  private boolean hasTransactions = false;
  private Disposable fetchTransactionsDisposable;
  private final Runnable startFetchTransactionsTask = () -> this.fetchTransactions(false);
  private PublishSubject<Context> topUpClicks = PublishSubject.create();

  TransactionsViewModel(AppcoinsApps applications, TransactionsAnalytics analytics,
      TransactionViewNavigator transactionViewNavigator,
      TransactionViewInteract transactionViewInteract, SupportInteractor supportInteractor) {
    this.applications = applications;
    this.analytics = analytics;
    this.transactionViewNavigator = transactionViewNavigator;
    this.transactionViewInteract = transactionViewInteract;
    this.supportInteractor = supportInteractor;
    this.disposables = new CompositeDisposable();
  }

  @Override protected void onCleared() {
    super.onCleared();
    hasTransactions = false;
    if (!disposables.isDisposed()) {
      disposables.dispose();
    }
    handler.removeCallbacks(startFetchTransactionsTask);
    handler.removeCallbacks(startGlobalBalanceTask);
  }

  public LiveData<NetworkInfo> defaultNetwork() {
    return defaultNetwork;
  }

  public LiveData<Wallet> defaultWallet() {
    return defaultWallet;
  }

  public LiveData<TransactionsModel> transactionsModel() {
    return transactionsModel;
  }

  public LiveData<CardNotification> dismissNotification() {
    return dismissNotification;
  }

  public MutableLiveData<GlobalBalance> getDefaultWalletBalance() {
    return defaultWalletBalance;
  }

  public void prepare() {
    if (disposables.isDisposed()) {
      disposables = new CompositeDisposable();
    }
    progress.postValue(true);
    disposables.add(transactionViewInteract.findNetwork()
        .subscribe(this::onDefaultNetwork, this::onError));
    disposables.add(transactionViewInteract.hasPromotionUpdate()
        .subscribeOn(Schedulers.io())
        .subscribe(showNotification::postValue, this::onError));
    disposables.add(transactionViewInteract.getUserLevel()
        .subscribeOn(Schedulers.io())
        .flatMap(userLevel -> transactionViewInteract.findWallet()
            .subscribeOn(Schedulers.io())
            .map(wallet -> {
              registerSupportUser(userLevel, wallet.address);
              return true;
            }))
        .subscribe(wallet -> {
        }, this::onError));
    handleTopUpClicks();
  }

  public void resetUnreadConversations() {
    supportInteractor.resetUnreadConversations();
  }

  public void handleUnreadConversationCount() {
    disposables.add(supportInteractor.getUnreadConversationCountListener()
        .subscribeOn(AndroidSchedulers.mainThread())
        .doOnNext(this::updateIntercomAnimation)
        .subscribe());
  }

  public void updateConversationCount() {
    disposables.add(supportInteractor.getUnreadConversationCount()
        .subscribeOn(AndroidSchedulers.mainThread())
        .doOnNext(this::updateIntercomAnimation)
        .subscribe());
  }

  private void updateIntercomAnimation(Integer count) {
    if (count == null || count == 0) {
      unreadMessages.setValue(false);
    } else {
      unreadMessages.setValue(true);
    }
  }

  private Completable publishMaxBonus() {
    if (fetchTransactionsError.getValue() != null) {
      return Completable.fromAction(
          () -> fetchTransactionsError.postValue(fetchTransactionsError.getValue()));
    }
    return transactionViewInteract.getLevels()
        .subscribeOn(Schedulers.io())
        .flatMap(levels -> {
          if (levels.getStatus()
              .equals(Levels.Status.OK)) {
            return Single.just(levels.getList()
                .get(levels.getList()
                    .size() - 1)
                .getBonus());
          }
          return Single.error(new IllegalStateException(levels.getStatus()
              .name()));
        })
        .doOnSuccess(fetchTransactionsError::postValue)
        .ignoreElement();
  }

  public void fetchTransactions(boolean shouldShowProgress) {
    handler.removeCallbacks(startFetchTransactionsTask);
    progress.postValue(shouldShowProgress);
    /*For specific address use: new Wallet("0x60f7a1cbc59470b74b1df20b133700ec381f15d3")*/
    if (fetchTransactionsDisposable != null && !fetchTransactionsDisposable.isDisposed()) {
      fetchTransactionsDisposable.dispose();
    }

    fetchTransactionsDisposable =
        transactionViewInteract.fetchTransactions(defaultWallet.getValue())
            .flatMapSingle(transactions -> transactionViewInteract.getCardNotifications()
                .onErrorReturnItem(Collections.emptyList())
                .flatMap(notifications -> applications.getApps()
                    .onErrorReturnItem(Collections.emptyList())
                    .map(applications -> new TransactionsModel(transactions, notifications,
                        applications))))
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .flatMapCompletable(
                transactionsModel -> publishMaxBonus().observeOn(AndroidSchedulers.mainThread())
                    .andThen(onTransactionModel(transactionsModel))
                    .andThen(Completable.fromAction(this::onTransactionsFetchCompleted)))
            .onErrorResumeNext(throwable -> publishMaxBonus())
            .observeOn(AndroidSchedulers.mainThread())
            .doAfterTerminate(transactionViewInteract::stopTransactionFetch)
            .subscribe(() -> {
            }, this::onError);
    disposables.add(fetchTransactionsDisposable);
  }

  private void getGlobalBalance() {
    disposables.add(Observable.zip(getAppcBalance(), getCreditsBalance(), getEthereumBalance(),
        this::updateWalletValue)
        .subscribe(globalBalance -> {
          handler.removeCallbacks(startGlobalBalanceTask);
          handler.postDelayed(startGlobalBalanceTask, GET_BALANCE_INTERVAL);
        }, Throwable::printStackTrace));
  }

  private GlobalBalance updateWalletValue(Pair<Balance, FiatValue> tokenBalance,
      Pair<Balance, FiatValue> creditsBalance, Pair<Balance, FiatValue> ethereumBalance) {
    String fiatValue = "";
    BigDecimal sumFiat = sumFiat(tokenBalance.second.getAmount(), creditsBalance.second.getAmount(),
        ethereumBalance.second.getAmount());
    if (sumFiat.compareTo(MINUS_ONE) > 0) {
      fiatValue = sumFiat.setScale(FIAT_SCALE, RoundingMode.FLOOR)
          .toString();
    }
    GlobalBalance currentGlobalBalance = defaultWalletBalance.getValue();
    GlobalBalance newGlobalBalance =
        new GlobalBalance(tokenBalance.first, creditsBalance.first, ethereumBalance.first,
            tokenBalance.second.getSymbol(), fiatValue, shouldShow(tokenBalance, 0.01),
            shouldShow(creditsBalance, 0.01), shouldShow(ethereumBalance, 0.0001));
    if (currentGlobalBalance != null) {
      if (!currentGlobalBalance.equals(newGlobalBalance)) {
        defaultWalletBalance.postValue(newGlobalBalance);
      }
    } else {
      defaultWalletBalance.postValue(newGlobalBalance);
    }
    return newGlobalBalance;
  }

  private Observable<Pair<Balance, FiatValue>> getAppcBalance() {
    return transactionViewInteract.getAppcBalance();
  }

  private Observable<Pair<Balance, FiatValue>> getEthereumBalance() {
    return transactionViewInteract.getEthereumBalance();
  }

  private Observable<Pair<Balance, FiatValue>> getCreditsBalance() {
    return transactionViewInteract.getCreditsBalance();
  }

  private boolean shouldShow(Pair<Balance, FiatValue> balance, Double threshold) {
    return balance.first.getStringValue()
        .length() > 0
        && Double.parseDouble(balance.first.getStringValue()) >= threshold
        && (balance.second.getAmount()
        .compareTo(MINUS_ONE) > 0)
        && balance.second.getAmount()
        .doubleValue() >= threshold;
  }

  private BigDecimal sumFiat(BigDecimal appcoinsFiatValue, BigDecimal creditsFiatValue,
      BigDecimal etherFiatValue) {
    BigDecimal fiatSum = MINUS_ONE;
    if (appcoinsFiatValue.compareTo(MINUS_ONE) > 0) {
      fiatSum = appcoinsFiatValue;
    }

    if (creditsFiatValue.compareTo(MINUS_ONE) > 0) {
      if (fiatSum.compareTo(MINUS_ONE) > 0) {
        fiatSum = fiatSum.add(creditsFiatValue);
      } else {
        fiatSum = creditsFiatValue;
      }
    }

    if (etherFiatValue.compareTo(MINUS_ONE) > 0) {
      if (fiatSum.compareTo(MINUS_ONE) > 0) {
        fiatSum = fiatSum.add(etherFiatValue);
      } else {
        fiatSum = etherFiatValue;
      }
    }
    return fiatSum;
  }

  private void onDefaultNetwork(NetworkInfo networkInfo) {
    defaultNetwork.postValue(networkInfo);
    disposables.add(transactionViewInteract.findWallet()
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(this::onDefaultWallet, this::onError));
  }

  private void onDefaultWallet(Wallet wallet) {
    defaultWallet.setValue(wallet);
    getGlobalBalance();
    fetchTransactions(true);
  }

  private Completable onTransactionModel(TransactionsModel transactionsModel) {
    return Completable.fromAction(() -> {
      transactionsModel.getTransactions();
      hasTransactions = !transactionsModel.getTransactions()
          .isEmpty() || hasTransactions;
      this.transactionsModel.setValue(transactionsModel);
      Boolean last = progress.getValue();
      if (transactionsModel.getTransactions()
          .size() > 0 && last != null && last) {
        progress.postValue(true);
      }
    });
  }

  private void onTransactionsFetchCompleted() {
    progress.postValue(false);
    if (!hasTransactions) {
      error.postValue(new ErrorEnvelope(C.ErrorCode.EMPTY_COLLECTION, "empty collection"));
    }
    handler.postDelayed(startFetchTransactionsTask, FETCH_TRANSACTIONS_INTERVAL);
  }

  public void showSettings(Context context) {
    transactionViewNavigator.openSettings(context);
  }

  public void showSend(Context context) {
    transactionViewNavigator.openSendView(context);
  }

  public void showDetails(Context context, Transaction transaction) {
    transactionViewNavigator.openTransactionsDetailView(context, transaction);
  }

  public void showMyAddress(Context context) {
    transactionViewNavigator.openMyAddressView(context, defaultWallet.getValue());
  }

  public void showTokens(Context context) {
    transactionViewNavigator.openTokensView(context, defaultWallet.getValue());
  }

  public void pause() {
    if (!disposables.isDisposed()) {
      disposables.dispose();
    }
    handler.removeCallbacks(startFetchTransactionsTask);
    handler.removeCallbacks(startGlobalBalanceTask);
  }

  public void onAppClick(AppcoinsApplication appcoinsApplication,
      ApplicationClickAction applicationClickAction, Context context) {
    String url = "https://" + appcoinsApplication.getUniqueName() + ".en.aptoide.com/";
    switch (applicationClickAction) {
      case SHARE:
        shareApp.setValue(url);
        break;
      case CLICK:
      default:
        transactionViewNavigator.navigateToBrowser(context, Uri.parse(url));
        analytics.openApp(appcoinsApplication.getUniqueName(),
            appcoinsApplication.getPackageName());
    }
  }

  public void showTopApps(Context context) {
    transactionViewNavigator.navigateToBrowser(context,
        Uri.parse("https://en.aptoide.com/store/bds-store/group/group-10867"));
  }

  public MutableLiveData<Boolean> shouldShowPromotionsNotification() {
    return showNotification;
  }

  public void showTopUp(Context context) {
    topUpClicks.onNext(context);
  }

  public MutableLiveData<Double> gamificationMaxBonus() {
    return gamificationMaxBonus;
  }

  public MutableLiveData<String> shareApp() {
    return shareApp;
  }

  public MutableLiveData<Double> onFetchTransactionsError() {
    return fetchTransactionsError;
  }

  public MutableLiveData<Boolean> getUnreadMessages() {
    return unreadMessages;
  }

  public void navigateToPromotions(Context context) {
    transactionViewNavigator.openPromotions(context);
  }

  public void onNotificationClick(CardNotification cardNotification,
      CardNotificationAction cardNotificationAction, Context context) {
    switch (cardNotificationAction) {
      case DISMISS:
        dismissNotification(cardNotification);
        break;
      case DISCOVER:
        transactionViewNavigator.navigateToBrowser(context,
            Uri.parse(InviteFriendsActivity.APTOIDE_TOP_APPS_URL));
        break;
      case UPDATE:
        transactionViewNavigator.openUpdateAppView(context,
            transactionViewInteract.retrieveUpdateUrl());
        dismissNotification(cardNotification);
        break;
      case BACKUP:
        transactionViewNavigator.navigateToBackup(context);
        break;
    }
  }

  private void dismissNotification(CardNotification cardNotification) {
    disposables.add(transactionViewInteract.dismissNotification(cardNotification)
        .subscribe(() -> dismissNotification.postValue(cardNotification), this::onError));
  }

  public void showSupportScreen() {
    supportInteractor.displayChatScreen();
  }

  private void registerSupportUser(Integer level, String walletAddress) {
    supportInteractor.registerUser(level, walletAddress);
  }

  private void handleTopUpClicks() {
    disposables.add(topUpClicks.throttleFirst(1, TimeUnit.SECONDS)
        .doOnNext(transactionViewNavigator::openTopUp)
        .subscribe());
  }

  public void clearShareApp() {
    shareApp.setValue(null);
  }
}
