import 'package:flutter/material.dart';

import '../../core/theme/stopbell_spacing.dart';
import 'bus_route.dart';
import 'bus_route_presentation.dart';

enum _SearchState { idle, loading, success, empty, error }

class BusRouteSearchScreen extends StatefulWidget {
  const BusRouteSearchScreen({
    super.key,
    required this.search,
    required this.onSelect,
  });

  final Future<List<BusRoute>> Function(String query) search;
  final ValueChanged<BusRoute> onSelect;

  @override
  State<BusRouteSearchScreen> createState() => _BusRouteSearchScreenState();
}

class _BusRouteSearchScreenState extends State<BusRouteSearchScreen> {
  final _queryController = TextEditingController();
  _SearchState _state = _SearchState.idle;
  List<BusRoute> _routes = const [];
  String? _lastQuery;
  String? _selectedCategory;

  List<String?> get _categories {
    final regions = _routes
        .map((route) => route.regionCategory)
        .nonNulls
        .toSet();
    return [
      null,
      if (regions.remove('서울')) '서울',
      if (regions.remove('경기')) '경기',
      ...regions,
    ];
  }

  @override
  void dispose() {
    _queryController.dispose();
    super.dispose();
  }

  Future<void> _search([String? retryQuery]) async {
    if (_state == _SearchState.loading) return;
    final query = (retryQuery ?? _queryController.text).trim();
    if (query.isEmpty) return;

    FocusScope.of(context).unfocus();
    setState(() {
      _lastQuery = query;
      _state = _SearchState.loading;
      _routes = const [];
      _selectedCategory = null;
    });
    try {
      final routes = await widget.search(query);
      if (!mounted) return;
      setState(() {
        _routes = routes;
        _state = routes.isEmpty ? _SearchState.empty : _SearchState.success;
      });
    } catch (_) {
      if (mounted) setState(() => _state = _SearchState.error);
    }
  }

  void _clearSearch() {
    _queryController.clear();
    setState(() {
      _lastQuery = null;
      _routes = const [];
      _selectedCategory = null;
      _state = _SearchState.idle;
    });
  }

  @override
  Widget build(BuildContext context) {
    final loading = _state == _SearchState.loading;
    return Padding(
      padding: const EdgeInsets.all(StopBellSpacing.screenPadding),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            controller: _queryController,
            enabled: !loading,
            decoration: InputDecoration(
              prefixIcon: const Icon(Icons.search),
              labelText: '노선번호',
              hintText: '노선번호를 입력하세요',
              suffixIcon: ValueListenableBuilder(
                valueListenable: _queryController,
                builder: (context, value, _) => value.text.isEmpty
                    ? const SizedBox.shrink()
                    : IconButton(
                        tooltip: '검색어 지우기',
                        onPressed: loading ? null : _clearSearch,
                        icon: const Icon(Icons.close),
                      ),
              ),
            ),
            textInputAction: TextInputAction.search,
            onSubmitted: (_) => _search(),
          ),
          const SizedBox(height: StopBellSpacing.md),
          FilledButton(
            onPressed: loading ? null : _search,
            child: const Text('검색'),
          ),
          if (_state == _SearchState.success) ...[
            const SizedBox(height: StopBellSpacing.xs),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  for (final category in _categories)
                    Padding(
                      padding: const EdgeInsets.only(right: StopBellSpacing.sm),
                      child: ChoiceChip(
                        key: ValueKey(category),
                        label: Text(category ?? '전체'),
                        selected: category == _selectedCategory,
                        selectedColor: Theme.of(context).colorScheme.primary,
                        labelStyle: TextStyle(
                          color: category == _selectedCategory
                              ? Theme.of(context).colorScheme.onPrimary
                              : Theme.of(context).colorScheme.onSurface,
                        ),
                        showCheckmark: false,
                        onSelected: (_) => setState(() {
                          _selectedCategory = category;
                        }),
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(height: StopBellSpacing.xs),
          ],
          Expanded(child: _buildResults()),
        ],
      ),
    );
  }

  Widget _buildResults() {
    switch (_state) {
      case _SearchState.idle:
        return const SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SizedBox(height: StopBellSpacing.lg),
              Icon(Icons.search),
              SizedBox(height: StopBellSpacing.sm),
              Text('노선번호를 입력해 검색하세요.'),
            ],
          ),
        );
      case _SearchState.loading:
        return const Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              CircularProgressIndicator(),
              SizedBox(height: StopBellSpacing.md),
              Text('노선 검색 중...'),
            ],
          ),
        );
      case _SearchState.empty:
        return const SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SizedBox(height: StopBellSpacing.lg),
              Icon(Icons.search_off),
              SizedBox(height: StopBellSpacing.sm),
              Text('검색 결과가 없어요.'),
            ],
          ),
        );
      case _SearchState.error:
        return Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('노선 검색에 실패했어요.'),
              const SizedBox(height: StopBellSpacing.md),
              OutlinedButton(
                onPressed: () => _search(_lastQuery),
                child: const Text('다시 시도'),
              ),
            ],
          ),
        );
      case _SearchState.success:
        return _buildRouteList();
    }
  }

  Widget _buildRouteList() {
    final visibleRoutes = _selectedCategory == null
        ? _routes
        : _routes
              .where((route) => route.regionCategory == _selectedCategory)
              .toList();
    final routesByDisplay = <(String, String?), List<BusRoute>>{};
    for (final route in _routes) {
      (routesByDisplay[(route.routeNumber, route.endpoints)] ??= []).add(route);
    }

    return ListView.builder(
      key: ValueKey(_selectedCategory),
      itemCount: visibleRoutes.length,
      itemBuilder: (context, index) {
        final route = visibleRoutes[index];
        final sameDisplay =
            routesByDisplay[(route.routeNumber, route.endpoints)]!;
        final sameRegion = sameDisplay.where(
          (other) => other.regionName == route.regionName,
        );
        final detail = sameDisplay.length == 1
            ? null
            : sameRegion.length == 1
            ? route.regionName
            : '${route.regionName} · 노선 ID ${route.id}';
        return _buildRouteRow(context, route, detail);
      },
    );
  }

  Widget _buildRouteRow(BuildContext context, BusRoute route, String? detail) {
    final theme = Theme.of(context);
    final presentation = BusRoutePresentation.forRoute(route, theme.brightness);
    final label = presentation.label;

    return Card(
      key: ValueKey(route.id),
      margin: const EdgeInsets.only(bottom: StopBellSpacing.xs),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => widget.onSelect(route),
        child: Padding(
          padding: const EdgeInsets.symmetric(
            horizontal: StopBellSpacing.md,
            vertical: StopBellSpacing.sm,
          ),
          child: Row(
            children: [
              Icon(Icons.directions_bus_outlined, color: presentation.color),
              const SizedBox(width: StopBellSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Wrap(
                      spacing: StopBellSpacing.sm,
                      crossAxisAlignment: WrapCrossAlignment.center,
                      children: [
                        Text(
                          route.routeNumber,
                          style: theme.textTheme.titleLarge!.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        if (label != null)
                          Text(
                            label,
                            style: theme.textTheme.labelSmall!.copyWith(
                              color: presentation.color,
                            ),
                          ),
                      ],
                    ),
                    Text(
                      route.endpoints ?? '운행 구간 정보 없음',
                      style: theme.textTheme.bodySmall,
                    ),
                    if (detail != null)
                      Text(detail, style: theme.textTheme.bodySmall),
                  ],
                ),
              ),
              const SizedBox(width: StopBellSpacing.xs),
              Icon(
                Icons.chevron_right,
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
