import 'package:flutter/material.dart';

import 'bus_route.dart';

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

  @override
  void dispose() {
    _queryController.dispose();
    super.dispose();
  }

  Future<void> _search([String? retryQuery]) async {
    if (_state == _SearchState.loading) return;
    final query = (retryQuery ?? _queryController.text).trim();
    if (query.isEmpty) return;

    setState(() {
      _lastQuery = query;
      _state = _SearchState.loading;
      _routes = const [];
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

  @override
  Widget build(BuildContext context) {
    final loading = _state == _SearchState.loading;
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            controller: _queryController,
            enabled: !loading,
            decoration: const InputDecoration(
              labelText: '노선번호',
              hintText: '노선번호를 입력하세요',
            ),
            textInputAction: TextInputAction.search,
            onSubmitted: (_) => _search(),
          ),
          const SizedBox(height: 12),
          FilledButton(
            onPressed: loading ? null : _search,
            child: const Text('검색'),
          ),
          const SizedBox(height: 16),
          Expanded(child: _buildResults()),
        ],
      ),
    );
  }

  Widget _buildResults() {
    switch (_state) {
      case _SearchState.idle:
        return const Center(child: Text('노선번호를 입력해 검색하세요.'));
      case _SearchState.loading:
        return const Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              CircularProgressIndicator(),
              SizedBox(height: 12),
              Text('노선 검색 중...'),
            ],
          ),
        );
      case _SearchState.empty:
        return const Center(child: Text('검색 결과가 없습니다.'));
      case _SearchState.error:
        return Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('노선 검색에 실패했습니다.'),
              const SizedBox(height: 12),
              OutlinedButton(
                onPressed: () => _search(_lastQuery),
                child: const Text('다시 시도'),
              ),
            ],
          ),
        );
      case _SearchState.success:
        return ListView.builder(
          itemCount: _routes.length,
          itemBuilder: (context, index) {
            final route = _routes[index];
            return ListTile(
              key: ValueKey(route.id),
              title: Text(route.routeNumber),
              subtitle: Text(route.regionName),
              onTap: () => widget.onSelect(route),
            );
          },
        );
    }
  }
}
